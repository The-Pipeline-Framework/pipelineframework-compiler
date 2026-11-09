package org.pipelineframework.processor.census;

import com.google.gson.Gson;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.pipelineframework.annotation.PipelineStep;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Maven build adapter, loaded explicitly as a core extension from the compiler artifact. */
public final class MavenCensusLifecycle extends AbstractMavenLifecycleParticipant {
    private volatile LocalCensusBuildHost host;
    private volatile String senderClasspath;
    private boolean pending;
    private Path stateDirectory;
    private URI collector;
    private boolean attempted;

    @Override public void afterProjectsRead(MavenSession session) {
        String os = System.getProperty("os.name", "");
        if (!os.startsWith("Linux") && !os.startsWith("Mac")) return;
        var properties = session.getUserProperties();
        String local = properties.getProperty("tpf.census.localCollector");
        if (local != null && !CensusRelease.ENABLED && !"true".equalsIgnoreCase(System.getenv("TPF_CENSUS_ENABLED"))) return;
        var endpoint = CensusRelease.endpoint(local);
        var configuration = CensusConfiguration.forMaven(properties);
        if (endpoint.isEmpty() || configuration.mode() != CensusConfiguration.Mode.OUTBOX) return;
        var request = session.getRequest();
        var delegate = request.getExecutionListener();
        if (delegate == null) return;
        request.setExecutionListener((ExecutionListener) Proxy.newProxyInstance(ExecutionListener.class.getClassLoader(),
                new Class<?>[] { ExecutionListener.class }, (proxy, method, args) -> {
                    if ("mojoStarted".equals(method.getName())) {
                        try { configure(((ExecutionEvent) args[0]).getMojoExecution(), configuration.stateDirectory(), endpoint.orElseThrow()); }
                        catch (Exception unavailable) { /* Optional build census. */ }
                    }
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                }));
    }

    private void configure(MojoExecution execution, Path state, URI endpoint) throws Exception {
        if (!"org.apache.maven.plugins".equals(execution.getGroupId())
                || !"maven-compiler-plugin".equals(execution.getArtifactId()) || !"compile".equals(execution.getGoal())) return;
        var source = execution.getConfiguration();
        if (source == null || !usesTpf(source)) return;
        var active = start(state, endpoint);
        if (active.isEmpty()) return;
        var configuration = new Xpp3Dom(source);
        var arguments = configuration.getChild("compilerArgs");
        if (arguments == null) {
            arguments = new Xpp3Dom("compilerArgs");
            configuration.addChild(arguments);
        }
        for (String value : active.orElseThrow().compilerArguments()) {
            var argument = new Xpp3Dom("arg");
            argument.setValue(value);
            arguments.addChild(argument);
        }
        execution.setConfiguration(configuration);
    }

    private boolean usesTpf(Xpp3Dom configuration) {
        var paths = configuration.getChild("annotationProcessorPaths");
        if (paths != null && Arrays.stream(paths.getChildren()).anyMatch(path -> {
            var group = path.getChild("groupId");
            var artifact = path.getChild("artifactId");
            return group != null && artifact != null && "org.pipelineframework".equals(group.getValue())
                    && "pipelineframework-compiler".equals(artifact.getValue());
        })) return true;
        var arguments = configuration.getChild("compilerArgs");
        return arguments != null && Arrays.stream(arguments.getChildren()).map(Xpp3Dom::getValue)
                .anyMatch(value -> value != null && value.startsWith("org.pipelineframework.processor."));
    }

    private synchronized java.util.Optional<LocalCensusBuildHost> start(Path state, URI endpoint) throws Exception {
        if (!attempted) {
            attempted = true;
            var receiver = new LocalCensusBuildHost(state);
            stateDirectory = state;
            collector = endpoint;
            receiver.startCollecting(() -> {
                try {
                    java.nio.file.Files.createDirectories(state.getParent());
                    senderClasspath = String.join(File.pathSeparator,
                            List.of(location(LocalCensusSender.class), location(Gson.class), location(PipelineStep.class)));
                } catch (Exception unavailable) {
                    // The worker still tries to persist usable handoffs.
                }
            }, staged -> {
                try { pending = staged > 0 || new CensusStateStore(state).hasPending(java.time.Clock.systemUTC()); }
                catch (Exception unavailable) { /* Leave retained state for another build. */ }
            });
            host = receiver;
        }
        return java.util.Optional.ofNullable(host);
    }

    private String location(Class<?> owner) throws Exception {
        return Path.of(owner.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    @Override public void afterSessionEnd(MavenSession session) {
        if (host == null) return;
        try (var active = host) {
            boolean settled = active.finishCollecting();
            String classpath = senderClasspath;
            if (classpath != null && (!settled || pending)) {
                // A local write may have committed just before the budget expired.
                // The sender checks the retained state after this process exits.
                LocalCensusSender.launchForMaven(stateDirectory, classpath, collector);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception unavailable) {
            // Maven does not wait for delivery or fail on census errors.
        }
    }
}
