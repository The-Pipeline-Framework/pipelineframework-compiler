package org.pipelineframework.processor.census;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.pipelineframework.processor.PipelineStepProcessor;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual javac executable and a separate host-owned loopback drain. */
@Isolated("Measures paired forked compiler wall times without other test fixtures competing")
public class CensusForkedJavacTest {
    @TempDir Path root;
    private static final String TIMING = "CENSUS_TEST_FINAL_ROUND_NS=";
    private record Timing(long wall, long finalRound) { }

    /** Verifies exit-safe handoff and optionally records a larger paired timing sample. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void forkedJavacLeavesReportForHost() throws Exception {
        writeFixture(root);
        var receipt = new AtomicInteger();
        var requests = new AtomicInteger();
        int delay = Integer.getInteger("tpf.census.loopbackDelay", 150);
        var server = collector(receipt, requests, delay);
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var transport = new HttpsCensusTransport(URI.create(endpoint + "/observations"));
            int trials = Integer.getInteger("tpf.census.forkTrials", 1);
            int warmups = trials > 1 ? 3 : 0;
            var offTimes = new ArrayList<Long>();
            var queueTimes = new ArrayList<Long>();
            var pairedDeltas = new ArrayList<Long>();
            var queueRounds = new ArrayList<Long>();
            var pairedRounds = new ArrayList<Long>();
            for (int i = 0; i < trials + warmups; i++) {
                Path state = root.resolve("state-" + i);
                Timing off, queued;
                try (var host = new LocalCensusBuildHost(state)) {
                    if (i % 2 == 0) {
                        off = javac(false, state, host);
                        queued = javac(true, state, host);
                    } else {
                        queued = javac(true, state, host);
                        off = javac(false, state, host);
                    }
                    assertFalse(Files.exists(state));
                    assertEquals(1, host.collect());
                }
                assertEquals(i, requests.get(), "javac must not send the report");
                assertTrue(Files.isRegularFile(state.resolve("state.json")), "pending state must survive javac exit");
                var configuration = new CensusConfiguration(CensusConfiguration.Mode.OUTBOX, state, Optional.empty());
                if (i == 0) {
                    assertEquals(0, BuildCensusOutbox.drain(configuration,
                            new HttpsCensusTransport(URI.create(endpoint + "/unavailable"))));
                }
                assertEquals(1, BuildCensusOutbox.drain(configuration, transport));
                assertEquals(i + 1, receipt.get());
                assertEquals(0, BuildCensusOutbox.drain(configuration, transport));
                if (i >= warmups) {
                    offTimes.add(off.wall());
                    queueTimes.add(queued.wall());
                    pairedDeltas.add(queued.wall() - off.wall());
                    queueRounds.add(queued.finalRound());
                    pairedRounds.add(queued.finalRound() - off.finalRound());
                }
            }
            System.out.printf(java.util.Locale.ROOT,
                    "Forked javac: %d paired trials (+%d warmup); all %d staged and received after exit; %d ms collector delay%n",
                    trials, warmups, trials + warmups, delay);
            System.out.printf(java.util.Locale.ROOT,
                    "Wall ms p50/p95: off %.2f/%.2f, outbox %.2f/%.2f, paired delta %.2f/%.2f%n",
                    percentile(offTimes, .50), percentile(offTimes, .95),
                    percentile(queueTimes, .50), percentile(queueTimes, .95),
                    percentile(pairedDeltas, .50), percentile(pairedDeltas, .95));
            System.out.printf(java.util.Locale.ROOT, "Final processing round ms p50/p95: outbox %.2f/%.2f, paired delta %.2f/%.2f%n",
                    percentile(queueRounds, .50), percentile(queueRounds, .95),
                    percentile(pairedRounds, .50), percentile(pairedRounds, .95));
        } finally {
            server.stop(0);
        }
    }

    /** Writes a minimal application with recognisable private test values. */
    private void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("pipeline.yaml"), """
                version: 1
                appName: Private Fixture
                basePackage: privatefixture
                transport: LOCAL
                steps:
                  - name: private-operation
                    operator: privatefixture.Service
                """);
        Files.writeString(directory.resolve("Service.java"), """
                package privatefixture;
                import io.smallrye.mutiny.Uni;
                import org.pipelineframework.service.ReactiveService;
                public class Service implements ReactiveService<String, String> {
                    public Uni<String> process(String input) { return Uni.createFrom().item(input); }
                }
                """);
    }

    /** Creates a loopback collector that can delay or reject acknowledgements. */
    private HttpServer collector(AtomicInteger receipt, AtomicInteger requests, int delay) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/unavailable", request -> {
            try (request) { request.sendResponseHeaders(503, -1); }
        });
        server.createContext("/observations", request -> {
            requests.incrementAndGet();
            try (request) {
                var body = JsonParser.parseReader(new java.io.InputStreamReader(request.getRequestBody(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if (body.get("schema_version").getAsInt() != 1 || !body.has("application_instance_id")
                        || body.toString().contains("privatefixture") || body.toString().contains(root.toString())) {
                    request.sendResponseHeaders(400, -1);
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(delay);
                receipt.incrementAndGet();
                request.sendResponseHeaders(204, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        return server;
    }

    /** Exercises Maven's normal compile lifecycle with a separate javac process and local host. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @EnabledIfSystemProperty(named = "tpf.census.mavenTrials", matches = "[1-9][0-9]*")
    void mavenForkHandsOffBeforeCompilerExit() throws Exception {
        int trials = Integer.getInteger("tpf.census.mavenTrials", 1);
        int delay = Integer.getInteger("tpf.census.loopbackDelay", 150);
        var receipts = new AtomicInteger();
        var requests = new AtomicInteger();
        var server = collector(receipts, requests, delay);
        server.start();
        try {
            var transport = new HttpsCensusTransport(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/observations"));
            var wall = new ArrayList<Long>();
            var rounds = new ArrayList<Long>();
            for (int i = 0; i < trials; i++) {
                Path state = root.resolve("maven-state-" + i);
                try (var host = new LocalCensusBuildHost(state)) {
                    Timing off, on;
                    if (i % 2 == 0) {
                        off = maven("off-" + i, false, host);
                        on = maven("on-" + i, true, host);
                    } else {
                        on = maven("on-" + i, true, host);
                        off = maven("off-" + i, false, host);
                    }
                    assertEquals(i, requests.get());
                    assertFalse(Files.exists(state), "javac must not write durable state in host mode");
                    assertEquals(1, host.collect(), "exactly one complete handoff must survive both Maven and javac exits");
                    assertEquals(0, host.collect());
                    assertEquals(1, host.drain(transport));
                    assertEquals(i + 1, receipts.get());
                    assertEquals(0, host.drain(transport));
                    wall.add(on.wall() - off.wall());
                    rounds.add(on.finalRound() - off.finalRound());
                }
            }
            System.out.printf(java.util.Locale.ROOT,
                    "Maven forked javac: %d/%d received, collector delay %d ms; paired final round ms p50/p95 %.2f/%.2f, wall %.2f/%.2f%n",
                    receipts.get(), trials, delay, percentile(rounds, .50), percentile(rounds, .95),
                    percentile(wall, .50), percentile(wall, .95));
        } finally {
            server.stop(0);
        }
    }

    /** Runs a Maven child with a receiver owned by the test launcher. */
    private Timing maven(String name, boolean enabled, LocalCensusBuildHost host) throws Exception {
        return maven(name, enabled, host::configure, List.of());
    }

    /** Proves that the standard Maven command can finish before an independent sender is acknowledged. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @EnabledIfSystemProperty(named = "tpf.census.senderTrials", matches = "[1-9][0-9]*")
    void mavenLifecycleStartsSenderThatOutlivesMaven() throws Exception {
        String extension = extensionClasspath();
        int trials = Integer.getInteger("tpf.census.senderTrials", 1);
        var rounds = new ArrayList<Long>();
        var wall = new ArrayList<Long>();
        for (int i = 0; i < trials; i++) {
            Path state = root.resolve("sender-state-" + i);
            var buildExited = new java.util.concurrent.CountDownLatch(1);
            var arrived = new java.util.concurrent.CountDownLatch(1);
            var received = new java.util.concurrent.CountDownLatch(1);
            var receipts = new AtomicInteger();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/observations", request -> {
                try (request) {
                    var body = JsonParser.parseReader(new java.io.InputStreamReader(request.getRequestBody(),
                            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!body.has("application_instance_id") || body.toString().contains(root.toString())) {
                        request.sendResponseHeaders(400, -1);
                        return;
                    }
                    arrived.countDown();
                    if (buildExited.await(4, TimeUnit.SECONDS)) {
                        receipts.incrementAndGet();
                        request.sendResponseHeaders(204, -1);
                        received.countDown();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            try {
                var configure = (java.util.function.Consumer<ProcessBuilder>) build -> {
                    build.environment().put("TPF_CENSUS_STATE_DIR", state.toString());
                    build.environment().put("GITHUB_ACTIONS", "true");
                };
                List<String> extensionOption = List.of(extension,
                        "-Dtpf.census.localCollector=http://127.0.0.1:" + server.getAddress().getPort() + "/observations");
                var offBefore = i % 2 == 0 ? Optional.of(maven("sender-off-" + i, false, configure, extensionOption))
                        : Optional.<Timing>empty();
                assertFalse(Files.exists(state), "no census state before the opted-in build");
                Timing on = maven("sender-on-" + i, true, configure, extensionOption);
                assertEquals(0, receipts.get(), "the collector cannot acknowledge before Maven exits");
                buildExited.countDown();
                assertTrue(arrived.await(5, TimeUnit.SECONDS), "the sender must reach the collector");
                assertTrue(received.await(5, TimeUnit.SECONDS), "the separate sender must deliver after Maven exits; "
                        + "state exists=" + Files.exists(state) + "\n" + Files.readString(root.resolve("sender-on-" + i + "/maven.log"))
                        + Files.readString(root.resolve("sender-on-" + i + "/processor-timing")));
                Path stateFile = state.resolve("state.json");
                for (int retry = 0; retry < 100 && pending(stateFile); retry++) Thread.sleep(20);
                assertFalse(pending(stateFile), "the sender must commit the acknowledgement");
                Timing off = offBefore.isPresent() ? offBefore.orElseThrow()
                        : maven("sender-off-" + i, false, configure, extensionOption);
                assertEquals(1, receipts.get(), "the opted-out build must not cause another receipt");
                rounds.add(on.finalRound() - off.finalRound());
                wall.add(on.wall() - off.wall());
            } finally {
                buildExited.countDown();
                server.stop(0);
            }
        }
        System.out.printf(java.util.Locale.ROOT,
                "Maven lifecycle + independent sender: %d/%d acknowledged; paired final round ms p50/p95 %.2f/%.2f, wall %.2f/%.2f%n",
                trials, trials, percentile(rounds, .50), percentile(rounds, .95), percentile(wall, .50), percentile(wall, .95));
    }

    /** Checks whether the sender still has a locally retained observation. */
    private boolean pending(Path state) throws Exception {
        return !JsonParser.parseString(Files.readString(state)).getAsJsonObject().getAsJsonObject("pending").isEmpty();
    }

    /** Reaps the sender after a parallel CI build, then recovers all observations without recompiling. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @EnabledIfSystemProperty(named = "tpf.census.parallel", matches = "true")
    void parallelCiBuildRetainsReportsWhenSenderIsKilled() throws Exception {
        int modules = 6;
        Path reactor = Files.createDirectory(root.resolve("reactor"));
        var entries = new StringBuilder();
        for (int i = 0; i < modules; i++) {
            String module = "application-" + i;
            createMavenProject(Files.createDirectory(reactor.resolve(module)), module);
            entries.append("<module>").append(module).append("</module>");
        }
        Path pom = reactor.resolve("pom.xml");
        Files.writeString(pom, """
                <project><modelVersion>4.0.0</modelVersion><groupId>test.local</groupId>
                <artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
                <modules>%s</modules></project>
                """.formatted(entries));
        Path state = root.resolve("retained-ci");
        var firstAttempt = new java.util.concurrent.CountDownLatch(1);
        var reaped = new java.util.concurrent.CountDownLatch(1);
        var receipts = new java.util.concurrent.CountDownLatch(modules);
        var applications = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/observations", exchange -> {
            try (exchange) {
                var event = JsonParser.parseReader(new java.io.InputStreamReader(exchange.getRequestBody(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if (requests.getAndIncrement() == 0) {
                    firstAttempt.countDown();
                    reaped.await(5, TimeUnit.SECONDS);
                    return;
                }
                if (applications.add(event.get("application_instance_id").getAsString())) receipts.countDown();
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            var options = List.of(extensionClasspath(), "-Dtpf.census.localCollector=http://127.0.0.1:"
                    + server.getAddress().getPort() + "/observations", "-T", "4");
            runCiMaven(pom, state, options, true, "parallel");
            Path stored = state.resolve("state.json");
            assertEquals(modules, JsonParser.parseString(Files.readString(stored)).getAsJsonObject()
                    .getAsJsonObject("pending").size());
            assertTrue(firstAttempt.await(5, TimeUnit.SECONDS));
            try (var processes = ProcessHandle.allProcesses()) {
                var sender = processes.filter(process -> process.info().arguments().stream().flatMap(java.util.Arrays::stream)
                                .anyMatch(state.toString()::equals))
                        .filter(process -> process.info().commandLine().orElse("").contains(LocalCensusSender.class.getName()))
                        .findFirst().orElseThrow();
                assertTrue(sender.destroyForcibly());
                sender.onExit().get(5, TimeUnit.SECONDS);
            }
            reaped.countDown();
            assertTrue(pending(stored));
            var retryOptions = new ArrayList<>(options);
            retryOptions.add("-Dmaven.main.skip=true");
            runCiMaven(pom, state, retryOptions, true, "retry");
            assertFalse(Files.readString(root.resolve("retry.log")).contains("Compiling "));
            assertTrue(receipts.await(8, TimeUnit.SECONDS));
            for (int retry = 0; retry < 100 && pending(stored); retry++) Thread.sleep(20);
            assertFalse(pending(stored));
            assertEquals(modules, applications.size());
            Path disposable = root.resolve("disposable-ci");
            Files.writeString(reactor.resolve("application-0/source/privatefixture/Service.java"), "\n",
                    java.nio.file.StandardOpenOption.APPEND);
            runCiMaven(pom, disposable, options, false, "disposable");
            assertTrue(Files.readString(root.resolve("disposable.log")).contains("Compiling "));
            assertFalse(Files.exists(disposable));
            assertEquals(modules + 1, requests.get());
            System.out.println("Parallel retained CI: " + modules + " recovered after reaping; disposable CI skipped");
        } finally {
            reaped.countDown();
            server.stop(0);
        }
    }

    /** Runs an offline reactor with explicit disposable or retained CI settings. */
    private void runCiMaven(Path pom, Path state, List<String> options, boolean retained, String name) throws Exception {
        Path compilerRoot = Path.of(System.getProperty("compiler.project.basedir"));
        var command = new ArrayList<>(List.of(compilerRoot.resolve("mvnw").toString(), "-o", "-B", "-f", pom.toString(),
                "-Dmaven.repo.local=" + compilerRoot.resolve(".m2/repository")));
        command.addAll(options);
        command.add("compile");
        Path log = root.resolve(name + ".log");
        var builder = new ProcessBuilder(command).directory(compilerRoot.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("TPF_CENSUS_ENABLED");
        builder.environment().put("GITHUB_ACTIONS", "true");
        builder.environment().put("TPF_CENSUS_STATE_PERSISTENT", Boolean.toString(retained));
        builder.environment().put("TPF_CENSUS_STATE_DIR", state.toString());
        Process build = builder.start();
        try {
            assertTrue(build.waitFor(50, TimeUnit.SECONDS), "parallel Maven build timed out");
            assertEquals(0, build.exitValue(), Files.readString(log));
        } finally {
            if (build.isAlive()) build.destroyForcibly();
        }
    }

    /** Packages the real extension with the release gate enabled for a loopback-only test. */
    private String extensionClasspath() throws Exception {
        Path classes = Path.of(MavenCensusLifecycle.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path jar = root.resolve("census-release-test.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar));
                var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = classes.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                output.putNextEntry(new java.util.jar.JarEntry(name));
                if (name.equals("META-INF/tpf/census-release.properties")) {
                    output.write("public_reporting=true\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    Files.copy(file, output);
                }
                output.closeEntry();
            }
        }
        return "-Dmaven.ext.class.path=" + String.join(java.io.File.pathSeparator, List.of(jar.toString(),
                Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(org.pipelineframework.annotation.PipelineStep.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()));
    }

    /** Measures one Maven command and its forked annotation processor. */
    private Timing maven(String name, boolean enabled, java.util.function.Consumer<ProcessBuilder> configure,
            List<String> mavenOptions) throws Exception {
        Path project = Files.createDirectory(root.resolve(name));
        Path pom = createMavenProject(project, name);
        Path compilerRoot = Path.of(System.getProperty("compiler.project.basedir"));
        Path timing = project.resolve("processor-timing");
        Path log = project.resolve("maven.log");
        var command = new ArrayList<>(List.of(compilerRoot.resolve("mvnw").toString(), "-o", "-B", "-f", pom.toString(),
                "-Dmaven.repo.local=" + compilerRoot.resolve(".m2/repository")));
        command.addAll(mavenOptions);
        command.add("compile");
        var builder = new ProcessBuilder(command)
                .directory(compilerRoot.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        configure.accept(builder);
        if (enabled && mavenOptions.stream().anyMatch(option -> option.startsWith("-Dmaven.ext.class.path="))) {
            builder.environment().remove("TPF_CENSUS_ENABLED");
        } else {
            builder.environment().put("TPF_CENSUS_ENABLED", Boolean.toString(enabled));
        }
        builder.environment().put("TPF_CENSUS_STATE_PERSISTENT", "true");
        builder.environment().put("TPF_CENSUS_TEST_TIMING_FILE", timing.toString());
        long start = System.nanoTime();
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(35, TimeUnit.SECONDS), "Maven compile timed out");
            long elapsed = System.nanoTime() - start;
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(log); }
                catch (java.io.IOException error) { return error.toString(); }
            });
            assertTrue(Files.isRegularFile(project.resolve("target/classes/privatefixture/Service.class")));
            var recorded = Files.readString(timing).split(" ");
            assertNotEquals(process.pid(), Long.parseLong(recorded[1]), "compiler must be forked");
            return new Timing(elapsed, Long.parseLong(recorded[0]));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /** Creates an independent Maven module with its own generated-output directory. */
    private Path createMavenProject(Path project, String name) throws Exception {
        Path source = Files.createDirectory(project.resolve("source"));
        writeFixture(source);
        Files.move(source.resolve("Service.java"), Files.createDirectory(source.resolve("privatefixture")).resolve("Service.java"));
        String classpath = xml(System.getProperty("java.class.path"));
        Path javac = Path.of(System.getProperty("java.home"), "bin", "javac");
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion><groupId>test.local</groupId>
                  <artifactId>%s</artifactId><version>1</version>
                  <build><sourceDirectory>source</sourceDirectory><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                    <version>3.14.0</version><configuration>
                      <fork>true</fork><executable>%s</executable><release>21</release>
                      <compilerArgs>
                        <arg>-classpath</arg><arg>%s</arg><arg>-processorpath</arg><arg>%s</arg>
                        <arg>-processor</arg><arg>%s</arg>
                        <arg>-Apipeline.generatedSourcesDir=%s</arg>
                        <arg>-Apipeline.config=%s</arg><arg>-Apipeline.orchestrator.generate=true</arg>
                      </compilerArgs>
                    </configuration></plugin></plugins></build>
                </project>
                """.formatted(name, xml(javac.toString()), classpath, classpath, TimingProcessor.class.getName(),
                xml(project.resolve("target/generated-sources/pipeline").toString()), xml(source.resolve("pipeline.yaml").toString())));
        return pom;
    }

    /** Escapes literal classpaths and paths embedded in a fixture POM. */
    private String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Times the actual process lifecycle with identical compiler inputs in both arms. */
    private Timing javac(boolean queue, Path state, LocalCensusBuildHost host) throws Exception {
        var name = state.getFileName() + (queue ? "-outbox" : "-off");
        Path output = Files.createDirectories(root.resolve(name));
        Path log = output.resolve("javac.log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac";
        String classpath = System.getProperty("java.class.path");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-classpath", classpath, "-processorpath", classpath,
                "-processor", TimingProcessor.class.getName(),
                "-s", output.toString(), "-d", output.toString(),
                "-Apipeline.generatedSourcesDir=" + output.resolve("generated-sources/pipeline"),
                "-Apipeline.config=" + root.resolve("pipeline.yaml"), "-Apipeline.orchestrator.generate=true",
                root.resolve("Service.java").toString());
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        host.configure(builder);
        builder.environment().put("TPF_CENSUS_ENABLED", Boolean.toString(queue));
        builder.environment().put("TPF_CENSUS_MODE", "outbox");
        builder.environment().put("TPF_CENSUS_STATE_PERSISTENT", "true");
        builder.environment().put("TPF_CENSUS_STATE_DIR", state.toString());
        long start = System.nanoTime();
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "forked javac timed out");
            long elapsed = System.nanoTime() - start;
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(log); }
                catch (java.io.IOException error) { return error.toString(); }
            });
            assertTrue(Files.isRegularFile(output.resolve("privatefixture/Service.class")));
            try (var lines = Files.lines(log)) {
                long finalRound = lines.filter(line -> line.startsWith(TIMING)).findFirst()
                        .map(line -> Long.parseLong(line.substring(TIMING.length()))).orElseThrow();
                return new Timing(elapsed, finalRound);
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /** Times the production processor's final callback with no instrumentation in the compiler artifact. */
    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    public static class TimingProcessor extends PipelineStepProcessor {
        /** Exposes the production processor options from the timing fixture. */
        @Override
        public Set<String> getSupportedOptions() {
            return Set.of(PipelineStepProcessor.class.getAnnotation(SupportedOptions.class).value());
        }

        /** Measures the final callback and leaves earlier rounds untouched. */
        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (!round.processingOver()) return super.process(annotations, round);
            long start = System.nanoTime();
            boolean claimed = super.process(annotations, round);
            long elapsed = System.nanoTime() - start;
            String timingFile = System.getenv("TPF_CENSUS_TEST_TIMING_FILE");
            if (timingFile == null) {
                System.err.println(TIMING + elapsed);
            } else {
                try {
                    var options = processingEnv.getOptions();
                    Files.writeString(Path.of(timingFile), elapsed + " " + ProcessHandle.current().pid()
                            + " mode=" + options.get("tpf.census.mode") + " socket=" + options.containsKey("tpf.census.hostSocket"));
                }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }
            return claimed;
        }
    }

    /** Uses the nearest-rank percentile for the measured process times. */
    private double percentile(List<Long> values, double quantile) {
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get((int) Math.ceil(quantile * sorted.size()) - 1) / 1_000_000.0;
    }
}
