package org.pipelineframework.processor;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.pipeline.PipelineJson;
import org.pipelineframework.orchestrator.release.PipelineContractDescriptor;
import org.pipelineframework.orchestrator.release.PipelinePlacementDescriptor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end compilation proof that REST/FUNCTION deployment metadata stays distinct from
 * placement-admitted local nested/self invocation and nested Await execution segments.
 */
class RestFunctionNestedCompositionTest {

    @TempDir Path directory;

    @Test
    void compilesRestFunctionWithNestedSelfCallNestedAwaitAndColocatedAspect() throws Exception {
        Path moduleDir = Files.createDirectories(directory.resolve("app"));
        Path yaml = Files.writeString(moduleDir.resolve("pipeline.yaml"), pipelineYaml());
        writeRuntimeMapping(moduleDir, """
            layout: pipeline-runtime
            validation: strict
            runtimes:
              application: {}
            modules:
              app:
                runtime: application
            defaults:
              runtime: application
              module: app
              synthetic:
                module: app
            """);

        Path classes = Files.createDirectories(moduleDir.resolve("classes"));
        Path generated = Files.createDirectories(moduleDir.resolve("target/generated-sources/pipeline"));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        boolean ok = compile(moduleDir, yaml, classes, generated, false, diagnostics);
        assertTrue(ok, diagnostics.getDiagnostics().toString());

        // 1. Platform metadata declares REST / FUNCTION (never relabeled LOCAL / COMPUTE).
        JsonObject platform = JsonParser.parseString(
            Files.readString(classes.resolve("META-INF/pipeline/platform.json"))).getAsJsonObject();
        assertEquals("FUNCTION", platform.get("platform").getAsString());
        assertEquals("REST", platform.get("transport").getAsString());
        assertEquals("app", platform.get("module").getAsString());

        // 2. Contract metadata parses cleanly via shared PipelineJson reader with REST / FUNCTION + placement.
        String contractJson = Files.readString(classes.resolve("META-INF/pipeline/pipeline-contract.json"));
        PipelineContractDescriptor contract = PipelineJson.mapper().readValue(contractJson, PipelineContractDescriptor.class);
        assertEquals(3, contract.schemaVersion());
        assertEquals("FUNCTION", contract.platform());
        assertEquals("REST", contract.transport());
        assertEquals("PIPELINE_RUNTIME", contract.runtimeLayout());
        assertEquals("app", contract.module());
        assertTrue(contract.composition().present());
        assertTrue(contract.placement().present());
        assertEquals(2, contract.placement().invocations().size());
        assertTrue(contract.placement().invocations().stream().allMatch(inv ->
            PipelinePlacementDescriptor.LOCAL_IN_SEGMENT.equals(inv.locality())
                && "app".equals(inv.module())
                && "application".equals(inv.runtimeId())));
        assertEquals(java.util.Set.of("app"), contract.placement().deployedModules());

        // 3. Order metadata & boundaries agree with in-segment LocalClientStep / LocalPipelineInvocation / DeferredCompletionStep.
        JsonObject order = JsonParser.parseString(
            Files.readString(classes.resolve("META-INF/pipeline/order.json"))).getAsJsonObject();
        assertEquals(1, order.get("boundarySchemaVersion").getAsInt());
        List<String> rootOrder = new ArrayList<>();
        order.getAsJsonArray("order").forEach(e -> rootOrder.add(e.getAsString()));
        assertTrue(rootOrder.stream().noneMatch(name -> name.endsWith("RestClientStep")),
            "Colocated REST/FUNCTION segment must not emit loopback RestClientSteps: " + rootOrder);
        assertTrue(rootOrder.stream().anyMatch(name -> name.endsWith("LocalClientStep")),
            "Expected colocated LocalClientStep in root order: " + rootOrder);
        assertTrue(rootOrder.stream().anyMatch(name -> name.contains("SideEffectLocalClientStep")),
            "Expected colocated persistence SideEffectLocalClientStep in root order: " + rootOrder);
        assertEquals(rootOrder.size(), order.getAsJsonArray("stepBoundaries").size());
        assertEquals(2, order.getAsJsonArray("definitionBoundaries").size());

        // 4. Generated REST/FUNCTION orchestrator adapters exist under rest-server.
        assertTrue(Files.exists(generated.resolve("rest-server/rfproof/orchestrator/service/PipelineRunResource.java")));
        assertTrue(Files.exists(generated.resolve("rest-server/rfproof/orchestrator/service/PipelineRunFunctionHandler.java")));

        // 5. Generated in-segment local steps, nested invocation beans, and colocated persistence aspect exist under orchestrator-client.
        List<Path> clientSources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(generated.resolve("orchestrator-client"))) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(clientSources::add);
        }
        assertFalse(clientSources.isEmpty());
        assertTrue(clientSources.stream().noneMatch(p -> p.getFileName().toString().endsWith("RestClientStep.java")),
            "Colocated REST/FUNCTION segment must not generate RestClientStep classes: " + clientSources);
        assertTrue(clientSources.stream().anyMatch(p -> p.getFileName().toString().startsWith("PipelineInvocation_")),
            "Expected generated PipelineInvocation_* classes: " + clientSources);
        assertTrue(clientSources.stream().anyMatch(p -> p.getFileName().toString().contains("DeferredCompletionStep")),
            "Expected generated nested DeferredCompletionStep class: " + clientSources);
        assertTrue(clientSources.stream().anyMatch(p -> p.getFileName().toString().contains("SideEffectLocalClientStep")),
            "Expected generated colocated persistence SideEffectLocalClientStep: " + clientSources);

        // 6. Branching metadata references LocalClientStep / PipelineInvocation_* / DeferredCompletionStep (zero RestClientStep).
        JsonObject branching = JsonParser.parseString(
            Files.readString(classes.resolve("META-INF/pipeline/branching.json"))).getAsJsonObject();
        List<String> branchingClasses = new ArrayList<>();
        branching.getAsJsonArray("steps").forEach(e ->
            branchingClasses.add(e.getAsJsonObject().get("runtimeStepClass").getAsString()));
        assertTrue(branchingClasses.stream().noneMatch(c -> c.endsWith("RestClientStep")),
            "Colocated REST/FUNCTION branching.json must not reference RestClientStep: " + branchingClasses);
        assertTrue(branchingClasses.stream().anyMatch(c -> c.endsWith("LocalClientStep")),
            "Expected LocalClientStep in branching.json: " + branchingClasses);
        assertTrue(branchingClasses.stream().anyMatch(c -> c.contains("PipelineInvocation_")),
            "Expected PipelineInvocation_* in branching.json: " + branchingClasses);
        assertTrue(branchingClasses.stream().anyMatch(c -> c.endsWith("DeferredCompletionStep")),
            "Expected DeferredCompletionStep in branching.json: " + branchingClasses);
    }

    @Test
    void compilesRestFunctionHttpBridgeWithMonolithLayoutWithoutNativeFunctionHandler() throws Exception {
        Path moduleDir = Files.createDirectories(directory.resolve("bridge-app"));
        Path yaml = Files.writeString(moduleDir.resolve("pipeline.yaml"), pipelineYaml());
        writeRuntimeMapping(moduleDir, """
            layout: monolith
            validation: strict
            runtimes:
              application: {}
            modules:
              app:
                runtime: application
            defaults:
              runtime: application
              module: app
              synthetic:
                module: app
            """);

        Path classes = Files.createDirectories(moduleDir.resolve("classes"));
        Path generated = Files.createDirectories(moduleDir.resolve("target/generated-sources/pipeline"));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        boolean ok = compile(moduleDir, yaml, classes, generated, true, diagnostics);
        assertTrue(ok, diagnostics.getDiagnostics().toString());

        PipelineContractDescriptor contract = PipelineJson.mapper().readValue(
            Files.readString(classes.resolve("META-INF/pipeline/pipeline-contract.json")),
            PipelineContractDescriptor.class);
        assertEquals("FUNCTION", contract.platform());
        assertEquals("REST", contract.transport());
        assertEquals("MONOLITH", contract.runtimeLayout());
        assertTrue(contract.placement().present());
        assertEquals(java.util.Set.of("app"), contract.placement().deployedModules());

        // HTTP bridge retains PipelineRunResource and suppresses native Lambda handler.
        assertTrue(Files.exists(generated.resolve("rest-server/rfproof/orchestrator/service/PipelineRunResource.java")));
        assertFalse(Files.exists(generated.resolve("rest-server/rfproof/orchestrator/service/PipelineRunFunctionHandler.java")));

        // Monolith + FUNCTION suppresses per-step server copies so no competing handlers exist.
        assertFalse(Files.exists(generated.resolve("rest-server/rfproof/pipeline")),
            "Monolith + FUNCTION must not emit competing per-step REST handlers");
    }

    @Test
    void rejectsUnmappedRestFunctionNestedInvocationAndCrossBoundaryPlacement() throws Exception {
        // Case 1: No pipeline.runtime.yaml (unmapped REST/FUNCTION) -> must fail closed!
        Path unmappedDir = Files.createDirectories(directory.resolve("unmapped"));
        Path unmappedYaml = Files.writeString(unmappedDir.resolve("pipeline.yaml"), pipelineYaml());
        var unmappedDiagnostics = new DiagnosticCollector<JavaFileObject>();
        boolean unmappedOk = compile(unmappedDir, unmappedYaml,
            Files.createDirectories(unmappedDir.resolve("classes")),
            Files.createDirectories(unmappedDir.resolve("generated")),
            false, unmappedDiagnostics);
        assertFalse(unmappedOk, "Unmapped REST/FUNCTION nested invocation must be rejected");
        assertTrue(unmappedDiagnostics.getDiagnostics().stream().anyMatch(d ->
            d.getMessage(null).contains("crosses a deployed boundary")
                || d.getMessage(null).contains("remote pipeline invocation is not implemented")),
            unmappedDiagnostics.getDiagnostics().toString());

        // Case 2: Cross-module placement (nested step placed in another module) -> must fail closed!
        Path splitDir = Files.createDirectories(directory.resolve("split"));
        Path splitYaml = Files.writeString(splitDir.resolve("pipeline.yaml"), pipelineYaml());
        writeRuntimeMapping(splitDir, """
            layout: modular
            validation: strict
            runtimes:
              app-rt: {}
              remote-rt: {}
            modules:
              app:
                runtime: app-rt
              remote-svc:
                runtime: remote-rt
            defaults:
              runtime: app-rt
              module: app
              synthetic:
                module: app
            steps:
              ProcessNestedWaitService: remote-svc
            """);
        var splitDiagnostics = new DiagnosticCollector<JavaFileObject>();
        boolean splitOk = compile(splitDir, splitYaml,
            Files.createDirectories(splitDir.resolve("classes")),
            Files.createDirectories(splitDir.resolve("generated")),
            false, splitDiagnostics);
        assertFalse(splitOk, "Cross-module nested invocation must be rejected");
        // Case 3: Split placement under pipeline-runtime (same runtime, different module) -> must fail closed!
        Path splitPrDir = Files.createDirectories(directory.resolve("split-pipeline-runtime"));
        Path splitPrYaml = Files.writeString(splitPrDir.resolve("pipeline.yaml"), pipelineYaml());
        writeRuntimeMapping(splitPrDir, """
            layout: pipeline-runtime
            validation: strict
            runtimes:
              application: {}
            modules:
              app:
                runtime: application
              remote-svc:
                runtime: application
            defaults:
              runtime: application
              module: app
              synthetic:
                module: app
            steps:
              ProcessNestedWaitService: remote-svc
            """);
        var splitPrDiagnostics = new DiagnosticCollector<JavaFileObject>();
        boolean splitPrOk = compile(splitPrDir, splitPrYaml,
            Files.createDirectories(splitPrDir.resolve("classes")),
            Files.createDirectories(splitPrDir.resolve("target/generated-sources/pipeline")),
            false, splitPrDiagnostics);
        assertFalse(splitPrOk, "Split module under pipeline-runtime must be rejected even in the same runtime");
        assertTrue(splitPrDiagnostics.getDiagnostics().stream().anyMatch(d ->
            d.getMessage(null).contains("crosses a deployed boundary")),
            splitPrDiagnostics.getDiagnostics().toString());
    }

    @Test
    void remotePipelineRuntimeStepModuleKeepsServerAdaptersWhenOrchestratorIsNotGenerated() throws Exception {
        Path moduleDir = Files.createDirectories(directory.resolve("remote-step-module"));
        Path yaml = Files.writeString(moduleDir.resolve("pipeline.yaml"), """
            version: 3
            appName: Remote Step Module Proof
            basePackage: remotestep
            transport: REST
            platform: COMPUTE
            contract: { input: WorkRequest, output: WorkReceipt }
            types:
              WorkRequest: { java: rfproof.Types.WorkRequest, fields: [[id, string]] }
              WorkReceipt: { java: rfproof.Types.WorkReceipt, fields: [[id, string], [status, string]] }
            steps:
              - name: Handle Work
                service: rfproof.Types.DirectSvc
                cardinality: ONE_TO_ONE
                input: WorkRequest
                output: WorkReceipt
                java: { input: rfproof.Types.DirectWork, output: rfproof.Types.WorkReceipt }
            """);
        writeRuntimeMapping(moduleDir, """
            layout: pipeline-runtime
            validation: strict
            runtimes:
              orchestrator: {}
              pipeline: {}
            modules:
              orchestrator-svc:
                runtime: orchestrator
              pipeline-svc:
                runtime: pipeline
            defaults:
              runtime: pipeline
              module: pipeline-svc
            """);
        Path classes = Files.createDirectories(moduleDir.resolve("classes"));
        Path generated = Files.createDirectories(moduleDir.resolve("target/generated-sources/pipeline"));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<String> options = List.of(
                "-proc:only",
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(),
                "-Apipeline.config=" + yaml,
                "-Apipeline.module=pipeline-svc",
                "-Apipeline.moduleDir=" + moduleDir,
                "-Apipeline.generatedSourcesDir=" + generated,
                "-Apipeline.orchestrator.generate=false");
            var task = compiler.getTask(null, manager, diagnostics, options, null,
                List.of(sourceFile("rfproof/Types.java", applicationSources())));
            task.setProcessors(List.of(new PipelineStepProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        // Remote step-host module must keep its REST server resource (not collapse to LocalClientStep).
        List<Path> serverSources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(generated.resolve("rest-server"))) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(serverSources::add);
        }
        assertTrue(serverSources.stream().anyMatch(p -> p.getFileName().toString().endsWith("Resource.java")),
            "Non-orchestrator pipeline-runtime step module must retain REST server resource: " + serverSources);
    }

    private boolean compile(
        Path moduleDir,
        Path yaml,
        Path classes,
        Path generated,
        boolean httpBridge,
        DiagnosticCollector<JavaFileObject> diagnostics
    ) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<String> options = new ArrayList<>(List.of(
                "-proc:only",
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(),
                "-Apipeline.config=" + yaml,
                "-Apipeline.module=app",
                "-Apipeline.moduleDir=" + moduleDir,
                "-Apipeline.generatedSourcesDir=" + generated,
                "-Apipeline.orchestrator.generate=true"));
            if (httpBridge) {
                options.add("-Apipeline.function.httpBridge=true");
            }
            var task = compiler.getTask(null, manager, diagnostics, options, null,
                List.of(sourceFile("rfproof/Types.java", applicationSources())));
            task.setProcessors(List.of(new PipelineStepProcessor()));
            return task.call();
        }
    }

    private static void writeRuntimeMapping(Path moduleDir, String content) throws Exception {
        Path configDir = Files.createDirectories(moduleDir.resolve("config"));
        Files.writeString(configDir.resolve("pipeline.runtime.yaml"), content);
    }

    private static JavaFileObject sourceFile(String relativePath, String source) {
        return new SimpleJavaFileObject(URI.create("string:///" + relativePath), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
    }

    private static String pipelineYaml() {
        return """
            version: 3
            appName: REST Function Nested Proof
            basePackage: rfproof
            transport: REST
            platform: FUNCTION
            contract: { input: WorkRequest, output: WorkReceipt }
            aspects:
              persistence:
                enabled: true
                scope: STEPS
                position: AFTER_STEP
                config:
                  targetSteps: [Record Receipt]
                  pluginImplementationClass: rfproof.Types.FakePersistencePlugin
            types:
              WorkRequest: { java: rfproof.Types.WorkRequest, fields: [[id, string]] }
              DirectWork: { java: rfproof.Types.DirectWork, fields: [[id, string]] }
              ReviewWork: { java: rfproof.Types.ReviewWork, fields: [[id, string]] }
              WorkChoice:
                java: rfproof.Types.WorkChoice
                variants: { direct: DirectWork, review: ReviewWork }
              PendingReview: { java: rfproof.Types.PendingReview, fields: [[id, string]] }
              ReviewDecision: { java: rfproof.Types.ReviewDecision, fields: [[id, string], [approved, bool]] }
              LoopOutcome:
                java: rfproof.Types.LoopOutcome
                variants: { retry: WorkRequest, settled: WorkReceipt }
              WorkReceipt: { java: rfproof.Types.WorkReceipt, fields: [[id, string], [status, string]] }
            pipelines:
              review-flow:
                input: ReviewWork
                output: LoopOutcome
                steps:
                  - name: Nested Wait
                    service: rfproof.Types.NestedWaitSvc
                    cardinality: ONE_TO_ONE
                    input: ReviewWork
                    output: ReviewDecision
                    java: { input: rfproof.Types.ReviewWork, output: rfproof.Types.ReviewDecision }
                    await:
                      operationOutput: { type: PendingReview, java: rfproof.Types.PendingReview }
                      timeout: PT5M
                      correlation: { strategy: interactionId }
                      transport: { type: interaction-api }
                  - name: Evaluate Decision
                    service: rfproof.Types.EvaluateSvc
                    cardinality: ONE_TO_ONE
                    input: ReviewDecision
                    output: LoopOutcome
                    java: { input: rfproof.Types.ReviewDecision, output: rfproof.Types.LoopOutcome }
            steps:
              - name: Route Work
                service: rfproof.Types.RouteSvc
                cardinality: ONE_TO_ONE
                input: WorkRequest
                output: WorkChoice
                java: { input: rfproof.Types.WorkRequest, output: rfproof.Types.WorkChoice }
              - name: Handle Direct
                service: rfproof.Types.DirectSvc
                cardinality: ONE_TO_ONE
                input: WorkChoice
                accepts: [DirectWork]
                output: WorkReceipt
                java: { input: rfproof.Types.DirectWork, output: rfproof.Types.WorkReceipt }
              - name: Run Review
                pipeline: review-flow
                cardinality: ONE_TO_ONE
                input: WorkChoice
                accepts: [ReviewWork]
                output: LoopOutcome
                java: { input: rfproof.Types.ReviewWork, output: rfproof.Types.LoopOutcome }
              - name: Record Receipt
                service: rfproof.Types.RecordSvc
                cardinality: ONE_TO_ONE
                input: LoopOutcome
                accepts: [WorkReceipt]
                output: WorkReceipt
                java: { input: rfproof.Types.WorkReceipt, output: rfproof.Types.WorkReceipt }
              - name: Continue Review
                pipeline: self
                cardinality: ONE_TO_ONE
                input: LoopOutcome
                accepts: [WorkRequest]
                output: WorkReceipt
                java: { input: rfproof.Types.WorkRequest, output: rfproof.Types.WorkReceipt }
            """;
    }

    private static String applicationSources() {
        return """
            package rfproof;
            public class Types {
              public record WorkRequest(String id) {}
              public record DirectWork(String id) {}
              public record ReviewWork(String id) {}
              public sealed interface WorkChoice permits WorkChoice.Direct, WorkChoice.Review {
                record Direct(DirectWork value) implements WorkChoice {}
                record Review(ReviewWork value) implements WorkChoice {}
              }
              public record PendingReview(String id) {}
              public record ReviewDecision(String id, boolean approved) {}
              public sealed interface LoopOutcome permits LoopOutcome.Retry, LoopOutcome.Settled {
                record Retry(WorkRequest value) implements LoopOutcome {}
                record Settled(WorkReceipt value) implements LoopOutcome {}
              }
              public record WorkReceipt(String id, String status) {}

              public static class FakePersistencePlugin implements org.pipelineframework.service.ReactiveSideEffectService<Object> {
                public io.smallrye.mutiny.Uni<Object> process(Object input) {
                  return io.smallrye.mutiny.Uni.createFrom().item(input);
                }
              }

              public static class RouteSvc implements org.pipelineframework.service.ReactiveService<WorkRequest, WorkChoice> {
                public io.smallrye.mutiny.Uni<WorkChoice> process(WorkRequest in) {
                  return io.smallrye.mutiny.Uni.createFrom().item(new WorkChoice.Direct(new DirectWork(in.id())));
                }
              }

              public static class DirectSvc implements org.pipelineframework.service.ReactiveService<DirectWork, WorkReceipt> {
                public io.smallrye.mutiny.Uni<WorkReceipt> process(DirectWork in) {
                  return io.smallrye.mutiny.Uni.createFrom().item(new WorkReceipt(in.id(), "direct"));
                }
              }

              public static class NestedWaitSvc implements org.pipelineframework.service.ReactiveService<ReviewWork, PendingReview> {
                public io.smallrye.mutiny.Uni<PendingReview> process(ReviewWork in) {
                  return io.smallrye.mutiny.Uni.createFrom().item(new PendingReview(in.id()));
                }
              }

              public static class EvaluateSvc implements org.pipelineframework.service.ReactiveService<ReviewDecision, LoopOutcome> {
                public io.smallrye.mutiny.Uni<LoopOutcome> process(ReviewDecision in) {
                  return io.smallrye.mutiny.Uni.createFrom().item(new LoopOutcome.Settled(new WorkReceipt(in.id(), "reviewed")));
                }
              }

              public static class RecordSvc implements org.pipelineframework.service.ReactiveService<WorkReceipt, WorkReceipt> {
                public io.smallrye.mutiny.Uni<WorkReceipt> process(WorkReceipt in) {
                  return io.smallrye.mutiny.Uni.createFrom().item(in);
                }
              }
            }
            """;
    }
}
