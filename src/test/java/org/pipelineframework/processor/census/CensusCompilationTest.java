package org.pipelineframework.processor.census;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.tools.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the service-loaded production compiler in a separate retained, private test host. */
public class CensusCompilationTest {
    @TempDir Path temporary;

    /** Checks that a subprocess exits even while its fake transport is blocked. */
    @Test void unfinishedDaemonReportingDoesNotKeepJvmAlive() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> runHost("exit"));
        assertTrue(Files.exists(temporary.resolve("exit-entered")));
    }

    /** Starts two JVMs on the same retained directory and checks the single daily attempt. */
    @Test void multipleCompilerProcessesShareOneRetainedDailyIdentity() throws Exception {
        try (var processes = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = processes.submit(() -> { runHost("observe"); return true; });
            var second = processes.submit(() -> { runHost("observe"); return true; });
            assertTrue(first.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, Files.readAllLines(temporary.resolve("observations.jsonl")).size());
        var state = JsonParser.parseString(Files.readString(temporary.resolve("exit-state/state.json"))).getAsJsonObject();
        assertEquals(1, state.getAsJsonObject("applications").size());
    }

    /** Exercises real javac processing for application, custom output, service and invalid builds. */
    @Test void productionHostPreviewsOnlyErrorFreeApplicationAuthority() throws Exception {
        Files.writeString(temporary.resolve("pipeline.yaml"), """
                version: 1
                appName: Private Customer Application
                basePackage: privatebusiness
                transport: LOCAL
                steps:
                  - name: private-operation
                    operator: privatebusiness.Service
                """);
        Files.writeString(temporary.resolve("Service.java"), """
                package privatebusiness;
                import io.smallrye.mutiny.Uni;
                import org.pipelineframework.service.ReactiveService;
                public class Service implements ReactiveService<String, String> {
                    public Uni<String> process(String input) { return Uni.createFrom().item(input); }
                }
                """);
        runHost("application");
        Path preview = temporary.resolve("application.json");
        assertTrue(Files.exists(preview), Files.readString(temporary.resolve("application.log")));
        String json = Files.readString(preview);
        var event = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("event");
        assertEquals("1", event.getAsJsonObject("sizes").get("steps").getAsString());
        assertFalse(json.contains("privatebusiness")); assertFalse(json.contains("Private Customer"));
        assertFalse(json.contains("private-operation"));
        // No census code, identifier, or dependency is copied into generated sources/resources.
        try (var files = Files.walk(temporary.resolve("application-generated"))) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                assertFalse(Files.readString(file).contains("processor.census"), file.toString());
        }
        runHost("packaged");
        assertFalse(Files.exists(temporary.resolve("packaged-classes/leak.json")));
        assertTrue(Files.exists(temporary.resolve("packaged-classes/META-INF/pipeline/pipeline-contract.json")), "Custom CLASS_OUTPUT is real compiled output");
        runHost("service");
        assertFalse(Files.exists(temporary.resolve("service.json")));
        Files.writeString(temporary.resolve("pipeline.yaml"), "appName: Broken\nsteps: [\n");
        runHost("invalid");
        assertFalse(Files.exists(temporary.resolve("invalid.json")));
    }
    /** Runs the production processor or state probe in a subprocess with controlled census settings. */
    private void runHost(String mode) throws Exception {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dtpf.census.enabled=true", "-Duser.home=" + temporary,
                "-cp", System.getProperty("java.class.path"), getClass().getName(), temporary.toString(), mode);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(temporary.resolve(mode + ".log").toFile());
        builder.environment().remove("TPF_CENSUS_ENABLED");
        builder.environment().put("TPF_CENSUS_STATE_PERSISTENT", "true");
        builder.environment().put("TPF_CENSUS_STATE_DIR", temporary.resolve("state").toString());
        Process process = builder.start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertEquals(0, process.waitFor(),
                    Files.readString(temporary.resolve(mode + ".log"))));
        } finally { process.destroyForcibly(); }
    }
    /** Runs the selected subprocess fixture and allows time for asynchronous inspection. */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); String mode = args[1];
        if (mode.equals("exit") || mode.equals("observe")) {
            Path source = root.resolve("exit.yaml"); Files.writeString(source, "private");
            var sizes = Map.of("pipelines", CountBand.ONE, "steps", CountBand.ONE, "types", CountBand.UNKNOWN, "blocks", CountBand.ZERO);
            var features = Map.of("nested_invocation", CountBand.ZERO, "one_to_many", CountBand.ZERO, "many_to_one", CountBand.ZERO,
                    "query", CountBand.ZERO, "command", CountBand.ZERO, "await", CountBand.ZERO);
            if (mode.equals("observe")) {
                var configuration = new CensusConfiguration(CensusConfiguration.Mode.REPORT, root.resolve("exit-state"), Optional.empty());
                new CensusStateStore(configuration.stateDirectory()).observe(new BuildCensusHostContext(source, configuration),
                        new BuildCensusSummary(sizes, features, BuildCensusSummary.Transport.LOCAL, BuildCensusSummary.Platform.COMPUTE,
                                BuildCensusSummary.Layout.UNSPECIFIED), java.time.Clock.systemUTC(), json -> {
                            Files.writeString(root.resolve("observations.jsonl"), json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            return true;
                        });
                return;
            }
            var reporter = new BuildCensusReporter(() -> json -> {
                Files.writeString(root.resolve("exit-entered"), "ready");
                new java.util.concurrent.CountDownLatch(1).await();
                return false;
            }, java.time.Clock.systemUTC());
            reporter.submit(new BuildCensusHostContext(source, new CensusConfiguration(CensusConfiguration.Mode.REPORT,
                    root.resolve("exit-state"), Optional.empty())), new BuildCensusSummary(sizes, features,
                    BuildCensusSummary.Transport.LOCAL, BuildCensusSummary.Platform.COMPUTE, BuildCensusSummary.Layout.UNSPECIFIED));
            for (int i = 0; i < 100 && !Files.exists(root.resolve("exit-entered")); i++) Thread.sleep(10);
            return;
        }
        Path generated = Files.createDirectories(root.resolve(mode + "-generated"));
        Path classes = Files.createDirectories(root.resolve(mode + "-classes"));
        Path preview = mode.equals("packaged") ? classes.resolve("leak.json") : root.resolve(mode + ".json");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(diagnostic -> System.err.println(diagnostic), Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(new java.io.PrintWriter(System.err), manager, diagnostic -> System.err.println(diagnostic), List.of(
                    "-proc:only", "-classpath", System.getProperty("java.class.path"),
                    "-processor", "org.pipelineframework.processor.PipelineStepProcessor",
                    "-s", generated.toString(), "-d", classes.toString(),
                    "-Apipeline.generatedSourcesDir=" + generated.resolve("generated-sources/pipeline"),
                    "-Apipeline.config=" + root.resolve("pipeline.yaml"),
                    "-Apipeline.orchestrator.generate=" + !mode.equals("service"),
                    "-Atpf.census.mode=preview", "-Atpf.census.preview=" + preview),
                    Collections.emptyList(), manager.getJavaFileObjects(root.resolve("Service.java"))).call();
            if (compiled == mode.equals("invalid")) throw new IllegalStateException("Unexpected compiler result for " + mode);
        }
        // Tests wait for the asynchronous preview; production compilation never waits.
        for (int i = 0; i < 100 && !Files.exists(preview); i++) Thread.sleep(20);
    }
}
