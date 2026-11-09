package org.pipelineframework.processor.census;

import com.google.gson.Gson;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.pipelineframework.annotation.PipelineStep;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Maven build adapter, loaded explicitly as a core extension from the compiler artifact. */
public final class MavenCensusLifecycle extends AbstractMavenLifecycleParticipant {
    private static final String MODE = "-Atpf.census.mode";
    private static final String ENABLED = "-Atpf.census.enabled";
    private record Registration(SessionReceiver receiver, ExecutionListener listener, ExecutionListener previous) { }
    private final ConcurrentHashMap<MavenSession, Registration> sessions = new ConcurrentHashMap<>();

    /** Installs the listener only for an eligible Maven session. */
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
        var receiver = new SessionReceiver(configuration.stateDirectory(), endpoint.orElseThrow());
        var listener = (ExecutionListener) Proxy.newProxyInstance(ExecutionListener.class.getClassLoader(),
                new Class<?>[] { ExecutionListener.class }, (proxy, method, args) -> {
                    if ("mojoStarted".equals(method.getName())) {
                        try { configure((ExecutionEvent) args[0], receiver); }
                        catch (Exception unavailable) { /* Optional build census. */ }
                    }
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        if (sessions.putIfAbsent(session, new Registration(receiver, listener, delegate)) == null)
            request.setExecutionListener(listener);
    }

    /** Adds the socket only to a TPF compilation using the outbox mode. */
    private void configure(ExecutionEvent event, SessionReceiver receiver) throws Exception {
        MojoExecution execution = event.getMojoExecution();
        if (!"org.apache.maven.plugins".equals(execution.getGroupId())
                || !"maven-compiler-plugin".equals(execution.getArtifactId()) || !"compile".equals(execution.getGoal())) return;
        var source = execution.getConfiguration();
        if (source == null || !usesTpf(source, event.getProject())) return;
        var values = compilerOptions(source, new PluginParameterExpressionEvaluator(event.getSession(), execution));
        if (values.stream().anyMatch(value -> isOption(value, MODE) && !value.equals(MODE + "=outbox"))) return;
        if (values.stream().anyMatch(value -> isOption(value, ENABLED) && !value.equalsIgnoreCase(ENABLED + "=true"))) return;
        boolean hasMode = values.stream().anyMatch(value -> isOption(value, MODE));
        var active = receiver.start();
        if (active.isEmpty()) return;
        var configuration = new Xpp3Dom(source);
        var arguments = configuration.getChild("compilerArgs");
        if (arguments == null) {
            arguments = new Xpp3Dom("compilerArgs");
            configuration.addChild(arguments);
        }
        if (!hasMode) appendArgument(arguments, MODE + "=outbox");
        appendArgument(arguments, active.orElseThrow().compilerSocketArgument());
        execution.setConfiguration(configuration);
    }

    /** Reads all three compiler-plugin argument forms with Maven's parameter interpolation. */
    private List<String> compilerOptions(Xpp3Dom configuration, PluginParameterExpressionEvaluator evaluator)
            throws org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException {
        var options = new ArrayList<String>();
        var args = configuration.getChild("compilerArgs");
        if (args != null) {
            for (var arg : args.getChildren()) {
                if (arg.getValue() != null && evaluator.evaluate(arg.getValue()) instanceof String value) options.add(value.strip());
            }
        }
        var single = configuration.getChild("compilerArgument");
        if (single != null && single.getValue() != null && evaluator.evaluate(single.getValue()) instanceof String value)
            options.add(value.strip());
        var mapped = configuration.getChild("compilerArguments");
        if (mapped != null) {
            for (String option : List.of(MODE, ENABLED)) {
                var argument = mapped.getChild(option.substring(1));
                if (argument == null) continue;
                var value = argument.getValue();
                options.add(value == null || value.isBlank() ? option : option + "=" + evaluator.evaluate(value));
            }
        }
        return options;
    }

    /** Matches a whole javac option name, with or without a value. */
    private boolean isOption(String value, String option) {
        return value.equals(option) || value.startsWith(option + "=");
    }

    /** Adds one argument without changing the project's existing argument representations. */
    private void appendArgument(Xpp3Dom arguments, String value) {
        var argument = new Xpp3Dom("arg");
        argument.setValue(value);
        arguments.addChild(argument);
    }

    /** Recognizes explicit processor registrations and classpath service discovery. */
    private boolean usesTpf(Xpp3Dom configuration, org.apache.maven.project.MavenProject project) {
        var proc = configuration.getChild("proc");
        if (proc != null && "none".equals(proc.getValue())) return false;
        var processors = configuration.getChild("annotationProcessors");
        if (processors != null && processors.getChildCount() > 0) {
            return Arrays.stream(processors.getChildren()).map(Xpp3Dom::getValue)
                    .anyMatch("org.pipelineframework.processor.PipelineStepProcessor"::equals);
        }
        var paths = configuration.getChild("annotationProcessorPaths");
        if (paths != null && Arrays.stream(paths.getChildren()).anyMatch(path -> {
            var group = path.getChild("groupId");
            var artifact = path.getChild("artifactId");
            return group != null && artifact != null && "org.pipelineframework".equals(group.getValue())
                    && "pipelineframework-compiler".equals(artifact.getValue());
        })) return true;
        var arguments = configuration.getChild("compilerArgs");
        if (arguments != null && Arrays.stream(arguments.getChildren()).map(Xpp3Dom::getValue)
                .anyMatch(value -> value != null && value.startsWith("org.pipelineframework.processor."))) return true;
        return (paths == null || paths.getChildCount() == 0) && project != null
                && project.getCompileArtifacts().stream().anyMatch(artifact -> "org.pipelineframework".equals(artifact.getGroupId())
                        && "pipelineframework-compiler".equals(artifact.getArtifactId()));
    }

    /** Removes the session receiver before starting the detached sender. */
    @Override public void afterSessionEnd(MavenSession session) {
        var registration = sessions.remove(session);
        if (registration == null) return;
        var request = session.getRequest();
        if (request.getExecutionListener() == registration.listener()) request.setExecutionListener(registration.previous());
        registration.receiver().finish();
    }

    /** Owns exactly one socket and the sender settings for a single Maven session. */
    private static final class SessionReceiver {
        private final Path state;
        private final URI endpoint;
        private LocalCensusBuildHost host;
        private volatile String senderClasspath;
        private boolean pending;
        private boolean attempted;

        /** Captures settings that must remain stable throughout the Maven session. */
        private SessionReceiver(Path state, URI endpoint) {
            this.state = state;
            this.endpoint = endpoint;
        }

        /** Starts one receiver even when Maven starts several compiler goals in parallel. */
        private synchronized Optional<LocalCensusBuildHost> start() throws Exception {
            if (!attempted) {
                attempted = true;
                var receiver = new LocalCensusBuildHost(state);
                receiver.startCollecting(() -> {
                    try {
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
            return Optional.ofNullable(host);
        }

        /** Resolves the exact artifacts needed by the detached sender. */
        private String location(Class<?> owner) throws Exception {
            return Path.of(owner.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        }

        /** Waits briefly for local state, then hands delivery to an independent process. */
        private synchronized void finish() {
            if (host == null) return;
            try (var active = host) {
                boolean settled = active.finishCollecting();
                String classpath = senderClasspath;
                if (classpath != null && (!settled || pending)) {
                    LocalCensusSender.launchForMaven(state, classpath, endpoint);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception unavailable) {
                // Maven does not wait for delivery or fail on census errors.
            } finally {
                host = null;
            }
        }
    }
}
