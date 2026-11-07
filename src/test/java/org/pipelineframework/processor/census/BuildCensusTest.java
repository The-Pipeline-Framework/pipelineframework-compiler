package org.pipelineframework.processor.census;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.JsonParser;
import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.processor.*;
import org.pipelineframework.processor.ir.*;
import org.pipelineframework.processor.parser.ParsedPipelineDefinitionCatalog;
import org.pipelineframework.processor.composition.*;
import org.pipelineframework.config.CardinalitySemantics;

class BuildCensusTest {
    @TempDir Path temporary;
    private BuildCensusSummary summary() {
        return new BuildCensusSummary(Map.of("pipelines", CountBand.ONE, "steps", CountBand.TWO_TO_FIVE,
                "types", CountBand.UNKNOWN, "blocks", CountBand.ZERO),
                Map.of("nested_invocation", CountBand.ZERO, "one_to_many", CountBand.ZERO, "many_to_one", CountBand.ZERO,
                    "query", CountBand.ONE, "command", CountBand.ZERO, "await", CountBand.ZERO),
                BuildCensusSummary.Transport.REST, BuildCensusSummary.Platform.COMPUTE, BuildCensusSummary.Layout.MODULAR);
    }
    private BuildCensusHostContext host(CensusConfiguration.Mode mode) throws Exception {
        Path source = temporary.resolve("private-company-pipeline.yaml");
        if (!Files.exists(source)) Files.writeString(source, "secret: never-transmit");
        return new BuildCensusHostContext(source, new CensusConfiguration(mode, temporary.resolve("state"), Optional.of(temporary.resolve("preview.json"))));
    }
    private Clock clock(String date) { return Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC); }
    @Test void countBandsAndVersionNormalizationAreBounded() {
        assertEquals(CountBand.UNKNOWN, CountBand.of(-1)); assertEquals(CountBand.ONE, CountBand.of(1));
        assertEquals(CountBand.TWO_TO_FIVE, CountBand.of(5)); assertEquals(CountBand.SIX_TO_TEN, CountBand.of(6));
        assertEquals(CountBand.OVER_HUNDRED, CountBand.of(101));
        assertEquals("26.9.4-dev", CensusEvent.normalizeVersion("26.9.4-SNAPSHOT"));
        assertEquals("unknown", CensusEvent.normalizeVersion("secret-project-release"));
        assertThrows(IllegalArgumentException.class, () -> new BuildCensusSummary(Map.of("private", CountBand.ONE),
                summary().featureCounts(), summary().transport(), summary().platform(), summary().runtimeLayout()));
    }
    @Test void retainedIdentitySuppressesBuildsButRenewsWeeklyAndOnVersionIndependentConfigurationChanges() throws Exception {
        var host = host(CensusConfiguration.Mode.REPORT); var store = new CensusStateStore(host.configuration().stateDirectory());
        List<String> events = new ArrayList<>(); CensusTransport transport = json -> { events.add(json); return true; };
        store.observe(host, summary(), clock("2026-10-04"), transport);
        store.observe(host, summary(), clock("2026-10-04"), transport);
        store.observe(host, summary(), clock("2026-10-05"), transport); // new ISO week
        store.observe(host, summary(), clock("2026-10-06"), transport);
        assertEquals(2, events.size());
        var first = JsonParser.parseString(events.getFirst()).getAsJsonObject();
        var second = JsonParser.parseString(events.getLast()).getAsJsonObject();
        assertEquals(first.get("installation_id"), second.get("installation_id"));
        assertEquals(first.get("application_instance_id"), second.get("application_instance_id"));
        assertFalse(events.toString().contains("private-company")); assertFalse(events.toString().contains("secret"));
        assertFalse(events.toString().contains("digest"));
        var changed = new BuildCensusSummary(summary().sizes(), summary().featureCounts(), summary().transport(),
                BuildCensusSummary.Platform.FUNCTION, summary().runtimeLayout());
        store.observe(host, changed, clock("2026-10-07"), transport);
        assertEquals(3, events.size());
        assertEquals(first.get("application_instance_id"), JsonParser.parseString(events.getLast()).getAsJsonObject().get("application_instance_id"));
        Files.writeString(temporary.resolve("second.yaml"), "another-secret");
        store.observe(new BuildCensusHostContext(temporary.resolve("second.yaml"), host.configuration()), summary(), clock("2026-10-07"), transport);
        assertNotEquals(first.get("application_instance_id"), JsonParser.parseString(events.getLast()).getAsJsonObject().get("application_instance_id"));
        Files.writeString(temporary.resolve("event.json"), events.getFirst());
    }
    @Test void failedDeliveryIsThrottledAndCorruptStateNeverResetsIdentity() throws Exception {
        var host = host(CensusConfiguration.Mode.REPORT); var store = new CensusStateStore(host.configuration().stateDirectory());
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        CensusTransport failure = json -> { attempts.incrementAndGet(); throw new java.io.IOException("offline"); };
        assertThrows(java.io.IOException.class, () -> store.observe(host, summary(), clock("2026-10-04"), failure));
        store.observe(host, summary(), clock("2026-10-04"), failure); assertEquals(1, attempts.get());
        assertThrows(java.io.IOException.class, () -> store.observe(host, summary(), clock("2026-10-05"), failure));
        Files.writeString(host.configuration().stateDirectory().resolve("state.json"), "corrupt");
        assertThrows(Exception.class, () -> store.observe(host, summary(), clock("2026-10-06"), failure));
        assertEquals(2, attempts.get());
    }
    @Test void previewUsesSamePayloadAndNeverCallsTransport() throws Exception {
        var host = host(CensusConfiguration.Mode.PREVIEW);
        var outcome = new CensusStateStore(host.configuration().stateDirectory()).observe(host, summary(), clock("2026-10-04"),
                json -> { fail("Preview transmitted"); return false; });
        assertEquals("eligible", outcome.reason());
        assertTrue(JsonParser.parseString(outcome.preview().orElseThrow()).getAsJsonObject().has("event"));
        assertFalse(outcome.preview().orElseThrow().contains(host.originalConfiguration().toString()));
    }
    @Test void policyFailsClosedAndDisabledReporterHasNoSideEffects() throws Exception {
        Properties properties = new Properties(); properties.setProperty("user.home", temporary.toString()); properties.setProperty("os.name", "Linux");
        var options = Map.of("tpf.telemetry.mode", "preview", "tpf.telemetry.preview", temporary.resolve("preview.json").toString());
        assertEquals(CensusConfiguration.Mode.OFF, CensusConfiguration.resolve(options, Map.of("TPF_TELEMETRY_DISABLED", "maybe"), properties).mode());
        assertEquals(CensusConfiguration.Mode.OFF, CensusConfiguration.resolve(options, Map.of("CI", "true"), properties).mode());
        assertEquals(CensusConfiguration.Mode.PREVIEW, CensusConfiguration.resolve(options, Map.of("CI", "true", "TPF_TELEMETRY_STATE_PERSISTENT", "true"), properties).mode());
        assertEquals(CensusConfiguration.Mode.OFF, CensusConfiguration.resolve(Map.of(), Map.of(), properties).mode());
        try (var reporter = new BuildCensusReporter(() -> { fail("Disabled initialized transport"); return json -> false; }, clock("2026-10-04"))) {
            reporter.submit(host(CensusConfiguration.Mode.OFF), summary());
        }
        assertFalse(Files.exists(temporary.resolve("state")));
    }
    @Test void workerDoesNotBlockCompilerAndIsDaemonOnly() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var reporter = new BuildCensusReporter(() -> json -> {
            assertTrue(Thread.currentThread().isDaemon()); entered.countDown(); release.await(2, TimeUnit.SECONDS); return false;
        }, clock("2026-10-04"))) {
            var host = host(CensusConfiguration.Mode.REPORT);
            assertTimeout(Duration.ofMillis(50), () -> reporter.submit(host, summary()));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); release.countDown();
        }
    }
    @Test void lockContentionAndSymlinksDoNotMutateState() throws Exception {
        var host = host(CensusConfiguration.Mode.REPORT); var store = new CensusStateStore(host.configuration().stateDirectory());
        store.observe(host, summary(), clock("2026-10-04"), json -> true);
        Path state = host.configuration().stateDirectory().resolve("state.json"); String before = Files.readString(state);
        try (var channel = java.nio.channels.FileChannel.open(host.configuration().stateDirectory().resolve("state.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertEquals("state_busy", store.observe(host, summary(), clock("2026-10-05"), json -> { fail("Contended state transmitted"); return false; }).reason());
        }
        assertEquals(before, Files.readString(state));
        Path target = temporary.resolve("protected.txt"); Files.writeString(target, "unchanged");
        Files.delete(state); Files.createSymbolicLink(state, target);
        assertThrows(Exception.class, () -> store.observe(host, summary(), clock("2026-10-05"), json -> true));
        assertEquals("unchanged", Files.readString(target));
    }
    @Test void errorsAndObserverExceptionsCannotTurnIntoReportsOrBuildFailures() {
        var ctx = new PipelineCompilationContext(mock(javax.annotation.processing.ProcessingEnvironment.class), Jsr269SourceInventory.empty(),
                new PipelineCompilerOptions(Map.of()), (severity, message) -> { });
        var called = new java.util.concurrent.atomic.AtomicInteger();
        PipelineCompilationPhase error = new PipelineCompilationPhase() {
            public String name() { return "error"; }
            public void execute(PipelineCompilationContext context) { context.getCompilerDiagnostics().error("invalid"); }
        };
        new PipelineCompiler(List.of(error), ignored -> called.incrementAndGet()).compile(ctx);
        assertEquals(0, called.get());
        var valid = new PipelineCompilationContext(mock(javax.annotation.processing.ProcessingEnvironment.class), Jsr269SourceInventory.empty(),
                new PipelineCompilerOptions(Map.of()), (severity, message) -> { });
        assertDoesNotThrow(() -> new PipelineCompiler(List.of(), ignored -> { throw new IllegalStateException("census unavailable"); }).compile(valid));
    }
    @Test void logicalGraphCountsReusedDefinitionsOnceWithoutModuleModels() throws Exception {
        var ctx = new PipelineCompilationContext(mock(javax.annotation.processing.ProcessingEnvironment.class), Jsr269SourceInventory.empty(),
                new PipelineCompilerOptions(Map.of()), (severity, message) -> { });
        ctx.setOriginalPipelineConfig(Optional.of(temporary.resolve("source.yaml"))); ctx.setOrchestratorGenerated(true);
        var root = new PipelineReference("$root"); var child = new PipelineReference("private-child");
        var type = ClassName.get("private.business", "Customer");
        StepDefinition invoke = StepDefinition.pipeline("private-invoke", type, type, StreamingShape.UNARY_UNARY, List.of(), false, child.logicalId());
        StepDefinition invokeAgain = StepDefinition.pipeline("private-again", type, type, StreamingShape.UNARY_UNARY, List.of(), false, child.logicalId());
        // Mockito constructs a minimal semantic operation without prohibited null constructor arguments.
        StepDefinition operation = mock(StepDefinition.class);
        when(operation.kind()).thenReturn(StepKind.INTERNAL); when(operation.pipelineReference()).thenReturn(Optional.empty());
        when(operation.deferredCompletion()).thenReturn(Optional.empty()); when(operation.streamingShapeHint()).thenReturn(StreamingShape.UNARY_STREAMING);
        ctx.setParsedPipelineDefinitionCatalog(new ParsedPipelineDefinitionCatalog(List.of(invoke, invokeAgain), Map.of(child.logicalId(), List.of(operation))));
        PipelineDefinition childDefinition = new PipelineDefinition(child, "Value", "Value", List.of(PipelineDefinitionStep.direct("x", "Value", "Value", CardinalitySemantics.ONE_TO_MANY)));
        PipelineDefinition rootDefinition = new PipelineDefinition(root, "Value", "Value", List.of(PipelineDefinitionStep.pipeline("a", "Value", "Value", child), PipelineDefinitionStep.pipeline("b", "Value", "Value", child)));
        var linker = new PipelineDefinitionLinker(reference -> Optional.of(childDefinition));
        ctx.setResolvedPipelineDefinitionGraph(linker.link(rootDefinition));
        ctx.setStepModels(List.of()); // module filtering cannot alter census sizes
        var result = new BuildCensusProjector().project(ctx).orElseThrow();
        assertEquals(CountBand.TWO_TO_FIVE, result.sizes().get("pipelines"));
        assertEquals(CountBand.TWO_TO_FIVE, result.sizes().get("steps"));
        String event = new CensusEvent(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026,10,4), "unknown", "unknown", 21, result).json();
        assertFalse(event.contains("private"));
        Files.writeString(Path.of("target/census-client-fixture.json"), event);
        ctx.getCompilerDiagnostics().error("semantic failure"); assertTrue(new BuildCensusProjector().project(ctx).isEmpty());
    }
    @Test void synchronousProjectionAndHandoffStayWithinBudgetOnLargeLogicalFixture() throws Exception {
        var ctx = new PipelineCompilationContext(mock(javax.annotation.processing.ProcessingEnvironment.class), Jsr269SourceInventory.empty(),
                new PipelineCompilerOptions(Map.of()), (severity, message) -> { });
        var host = host(CensusConfiguration.Mode.REPORT);
        ctx.setOriginalPipelineConfig(Optional.of(host.originalConfiguration())); ctx.setOrchestratorGenerated(true);
        var root = new PipelineReference("$root"); var child = new PipelineReference("private-child");
        var type = ClassName.get("private.business", "Value");
        List<StepDefinition> authored = new ArrayList<>(); List<PipelineDefinitionStep> linked = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            authored.add(StepDefinition.pipeline("invoke" + i, type, type, StreamingShape.UNARY_UNARY, List.of(), false, child.logicalId()));
            linked.add(PipelineDefinitionStep.pipeline("invoke" + i, "Value", "Value", child));
        }
        var operation = mock(StepDefinition.class);
        when(operation.kind()).thenReturn(StepKind.INTERNAL); when(operation.pipelineReference()).thenReturn(Optional.empty());
        when(operation.deferredCompletion()).thenReturn(Optional.empty());
        ctx.setParsedPipelineDefinitionCatalog(new ParsedPipelineDefinitionCatalog(authored, Map.of(child.logicalId(), List.of(operation))));
        var childDefinition = new PipelineDefinition(child, "Value", "Value", List.of(PipelineDefinitionStep.direct("x", "Value", "Value", CardinalitySemantics.ONE_TO_ONE)));
        var rootDefinition = new PipelineDefinition(root, "Value", "Value", linked);
        ctx.setResolvedPipelineDefinitionGraph(new PipelineDefinitionLinker(reference -> Optional.of(childDefinition)).link(rootDefinition));
        var projector = new BuildCensusProjector(); var blocked = new CountDownLatch(1);
        try (var reporter = new BuildCensusReporter(() -> json -> { blocked.await(); return false; }, clock("2026-10-04"))) {
            for (int i = 0; i < 50; i++) reporter.submit(host, projector.project(ctx).orElseThrow());
            long[] samples = new long[100];
            for (int i = 0; i < samples.length; i++) {
                long start = System.nanoTime(); reporter.submit(host, projector.project(ctx).orElseThrow());
                samples[i] = System.nanoTime() - start;
            }
            Arrays.sort(samples); double p95 = samples[94] / 1_000_000.0;
            System.out.println("Build census projection/handoff p95 (501 logical locations): " + p95 + " ms");
            assertTrue(p95 < 10, "Synchronous census budget exceeded: " + p95 + " ms");
        } finally { blocked.countDown(); }
    }

    @Test void concurrentFirstUseHasOneRetainedIdentityAndOneDailyAttempt() throws Exception {
        var host = host(CensusConfiguration.Mode.REPORT);
        var sent = new java.util.concurrent.atomic.AtomicInteger();
        try (var builders = Executors.newFixedThreadPool(8)) {
            List<Future<CensusStateStore.Outcome>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) results.add(builders.submit(() ->
                    new CensusStateStore(host.configuration().stateDirectory()).observe(host, summary(), clock("2026-10-04"),
                            json -> { sent.incrementAndGet(); return true; })));
            for (var result : results) result.get(3, TimeUnit.SECONDS);
        }
        assertEquals(1, sent.get());
        var state = JsonParser.parseString(Files.readString(host.configuration().stateDirectory().resolve("state.json"))).getAsJsonObject();
        assertEquals(1, state.getAsJsonObject("applications").size());
        assertFalse(state.toString().contains(host.originalConfiguration().toString()));
        Path blockedDirectory = temporary.resolve("not-a-directory"); Files.writeString(blockedDirectory, "unchanged");
        assertThrows(java.io.IOException.class, () -> new CensusStateStore(blockedDirectory)
                .observe(host, summary(), clock("2026-10-05"), json -> { fail("Inaccessible state transmitted"); return false; }));
        assertEquals("unchanged", Files.readString(blockedDirectory));
    }

    @Test void previewExplainsLockSuppressionWithoutInventingIdentities() throws Exception {
        var host = host(CensusConfiguration.Mode.PREVIEW);
        new CensusStateStore(host.configuration().stateDirectory()).observe(host, summary(), clock("2026-10-04"), json -> false);
        Path output = host.configuration().previewFile().orElseThrow();
        try (var channel = java.nio.channels.FileChannel.open(host.configuration().stateDirectory().resolve("state.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock();
             var reporter = new BuildCensusReporter(() -> { fail("Preview initialized network"); return json -> false; }, clock("2026-10-04"))) {
            reporter.submit(host, summary());
            for (int i = 0; i < 100 && !Files.exists(output); i++) Thread.sleep(10);
            var preview = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
            assertEquals("state_busy", preview.get("reason").getAsString());
            assertFalse(preview.get("event_available").getAsBoolean()); assertFalse(preview.has("event"));
        }
    }

    @Test void weeklyRenewalUsesUtcIsoWeekAcrossYearBoundary() throws Exception {
        var host = host(CensusConfiguration.Mode.REPORT); var store = new CensusStateStore(host.configuration().stateDirectory());
        var sent = new java.util.concurrent.atomic.AtomicInteger(); CensusTransport transport = json -> { sent.incrementAndGet(); return true; };
        store.observe(host, summary(), clock("2026-12-31"), transport);
        store.observe(host, summary(), clock("2027-01-01"), transport);
        assertEquals(1, sent.get(), "January 1 belongs to the same ISO week as December 31");
        store.observe(host, summary(), Clock.fixed(Instant.parse("2027-01-04T00:01:00Z"), ZoneId.of("Pacific/Honolulu")), transport);
        assertEquals(2, sent.get(), "Renewal uses UTC Monday, regardless of the host clock zone");
    }
    @Test void saturatedQueueDropsExcessAndCoalescesTheLatestPendingApplication() throws Exception {
        var first = host(CensusConfiguration.Mode.REPORT); var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1); var delivered = new CountDownLatch(9);
        List<String> events = new CopyOnWriteArrayList<>();
        try (var reporter = new BuildCensusReporter(() -> json -> {
            entered.countDown(); release.await(); events.add(json); delivered.countDown(); return true;
        }, clock("2026-10-04"))) {
            reporter.submit(first, summary()); assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 9; i++) {
                Path source = Files.writeString(temporary.resolve("pending" + i + ".yaml"), "private");
                var pending = new BuildCensusHostContext(source, first.configuration()); reporter.submit(pending, summary());
                if (i == 0) reporter.submit(pending, new BuildCensusSummary(summary().sizes(), summary().featureCounts(),
                        summary().transport(), BuildCensusSummary.Platform.FUNCTION, summary().runtimeLayout()));
            }
            release.countDown(); assertTrue(delivered.await(4, TimeUnit.SECONDS));
            assertEquals(9, events.size());
            assertEquals(1, events.stream().map(json -> JsonParser.parseString(json).getAsJsonObject())
                    .filter(event -> event.get("platform").getAsString().equals("FUNCTION")).count());
        } finally { release.countDown(); }
    }

}
