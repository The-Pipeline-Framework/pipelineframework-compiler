package org.pipelineframework.processor;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipelineframework.processor.phase.*;
import org.pipelineframework.processor.renderer.LocalPipelineInvocationRenderer;
import org.pipelineframework.processor.util.PipelineOrderMetadataGenerator;
import org.pipelineframework.processor.util.PipelineBranchingMetadataGenerator;

import static org.junit.jupiter.api.Assertions.*;

class LocalOrdinaryAwaitLoweringTest {
    @TempDir Path directory;

    @Test
    void rejectsCollidingOrdinaryChildNamesBeforeModelDeduplication() throws Exception {
        Path yaml = directory.resolve("pipeline.yaml");
        Files.writeString(yaml, """
            version: 3
            appName: LOCAL step name collision
            basePackage: proof
            transport: LOCAL
            contract: { input: Input, output: Result }
            types:
              Input: { java: proof.Types.Input, fields: [[value, string]] }
              Ready: { java: proof.Types.Ready, fields: [[value, string]] }
              Result: { java: proof.Types.Result, fields: [[value, string]] }
            pipelines:
              prepare:
                input: Input
                output: Result
                steps:
                  - { name: Prepare, service: proof.Types.Prepare, cardinality: ONE_TO_ONE, input: Input, output: Ready }
                  - { name: Process prepare, service: proof.Types.Finish, cardinality: ONE_TO_ONE, input: Ready, output: Result }
            steps:
              - name: Run preparation
                pipeline: prepare
                cardinality: ONE_TO_ONE
                input: Input
                output: Result
                java: { input: proof.Types.Input, output: proof.Types.Result }
            layout: monolith
            """);
        String source = """
            package proof;
            public class Types {
              public record Input(String value) {}
              public record Ready(String value) {}
              public record Result(String value) {}
              public static class Prepare implements org.pipelineframework.service.ReactiveService<Input, Ready> {
                public io.smallrye.mutiny.Uni<Ready> process(Input input) {
                  return io.smallrye.mutiny.Uni.createFrom().item(new Ready(input.value()));
                }
              }
              public static class Finish implements org.pipelineframework.service.ReactiveService<Ready, Result> {
                public io.smallrye.mutiny.Uni<Result> process(Ready input) {
                  return io.smallrye.mutiny.Uni.createFrom().item(new Result(input.value()));
                }
              }
            }
            """;
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        assertFalse(compile(yaml, source, List.of(new PipelineDiscoveryPhase(), new PipelineBranchPlanningPhase(),
            new ModelExtractionPhase()), diagnostics));
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(diagnostic -> diagnostic.getMessage(null).contains(
            "Authored steps 'Prepare' and 'Process prepare' in LOCAL definition 'prepare' share generated service name "
                + "'ProcessPrepareService'")), diagnostics.getDiagnostics().toString());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,false", "false,false,true"})
    void physicalOrderPreservesTransparentAliasesAndDoesNotGuessMissingFacts(boolean removeFacts, boolean itemized,
                                                                           boolean explicitRoot) throws Exception {
        Path yaml = directory.resolve("pipeline.yaml");
        Files.writeString(yaml, """
            version: 3
            appName: Aliased physical boundary
            basePackage: proof
            transport: LOCAL
            types:
              Value: { java: proof.Types.Value, fields: [[value, string]] }
              InputAlias: { alias: Value }
              OutputAlias: { alias: Value }
              UnusedAlias: { alias: Value }
            steps:
              - name: Identity
                service: proof.Types.Identity
                cardinality: ONE_TO_ONE
                input: InputAlias
                output: OutputAlias
            layout: monolith
            """);
        String source = """
            package proof;
            public class Types {
              public record Value(String value) {}
              public static class Identity implements org.pipelineframework.service.ReactiveService<Value, Value> {
                public io.smallrye.mutiny.Uni<Value> process(Value input) {
                  return io.smallrye.mutiny.Uni.createFrom().item(input);
                }
              }
            }
            """;
        if (explicitRoot) Files.writeString(yaml, Files.readString(yaml)
            + "contract: { input: UnusedAlias, output: UnusedAlias }\n");
        if (itemized) {
            Files.writeString(yaml, Files.readString(yaml).replace("steps:\n", """
                contract: { input: InputAlias, output: OutputAlias }
                pipelines:
                  expand:
                    input: InputAlias
                    output: OutputAlias
                    steps:
                      - name: Expand
                        service: proof.Types.Identity
                        cardinality: ONE_TO_MANY
                        input: InputAlias
                        output: OutputAlias
                steps:
                """).replace("service: proof.Types.Identity\n    cardinality: ONE_TO_ONE",
                    "pipeline: expand\n    cardinality: ONE_TO_MANY\n    java: { input: proof.Types.Value, output: proof.Types.Value }"));
            source = source.replace("ReactiveService<Value, Value>", "ReactiveStreamingService<Value, Value>")
                .replace("Uni<Value>", "Multi<Value>").replace("Uni.createFrom()", "Multi.createFrom()");
        }
        var phases = new ArrayList<PipelineCompilationPhase>(List.of(
            new PipelineDiscoveryPhase(), new PipelineBranchPlanningPhase(), new ModelExtractionPhase(),
            new PipelineSemanticAnalysisPhase(), new PipelineTargetResolutionPhase()));
        phases.add(new PipelineCompilationPhase() {
            @Override public String name() { return "Physical boundary identity proof"; }
            @Override public void execute(PipelineCompilationContext ctx) throws Exception {
                if (removeFacts) ctx.setStepModels(ctx.getStepModels().stream().map(model -> model.toBuilder()
                    .inputMapping(org.pipelineframework.processor.ir.TypeMapping.withoutMapper(model.inboundDomainType()))
                    .outputMapping(org.pipelineframework.processor.ir.TypeMapping.withoutMapper(model.outboundDomainType()))
                    .build()).toList());
                new LocalPipelineInvocationRenderer().render(ctx, directory.resolve("generated"));
                new PipelineOrderMetadataGenerator(ctx.getProcessingEnv()).writeOrderMetadata(ctx);
                new org.pipelineframework.processor.util.PipelineContractMetadataGenerator(ctx.getProcessingEnv()).writePipelineContract(ctx);
            }
        });
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        assertTrue(compile(yaml, source, phases, diagnostics), diagnostics.getDiagnostics().toString());
        var metadata = JsonParser.parseString(Files.readString(directory.resolve("classes/META-INF/pipeline/order.json")))
            .getAsJsonObject();
        assertEquals(1, metadata.getAsJsonArray("order").size());
        var contract = JsonParser.parseString(Files.readString(directory.resolve("classes/META-INF/pipeline/pipeline-contract.json")))
            .getAsJsonObject();
        // Exercise the actual shared reader, not just a permissive JSON-tree inspection.
        assertEquals(3, contract.get("schemaVersion").getAsInt());
        var parsedContract = org.pipelineframework.config.pipeline.PipelineJson.mapper().readValue(
            contract.toString(), org.pipelineframework.orchestrator.release.PipelineContractDescriptor.class);
        assertEquals(3, parsedContract.schemaVersion());
        var rootBoundary = contract.getAsJsonObject("rootBoundary");
        assertEquals(1, rootBoundary.get("schemaVersion").getAsInt());
        assertEquals(explicitRoot ? "UnusedAlias" : "InputAlias", rootBoundary.get("inputCanonicalTypeId").getAsString());
        assertEquals(explicitRoot ? "UnusedAlias" : "OutputAlias", rootBoundary.get("outputCanonicalTypeId").getAsString());
        if (!itemized) {
            var semanticStep = contract.getAsJsonArray("steps").get(0).getAsJsonObject();
            assertEquals("proof.Types.Value", semanticStep.get("inputTypeId").getAsString());
            assertEquals("proof.Types.Value", semanticStep.get("outputTypeId").getAsString());
        }
        if (removeFacts) {
            assertFalse(metadata.has("boundarySchemaVersion"));
            assertFalse(metadata.has("stepBoundaries"));
        } else {
            assertEquals(1, metadata.get("boundarySchemaVersion").getAsInt());
            var boundary = metadata.getAsJsonArray("stepBoundaries").get(0).getAsJsonObject();
            assertEquals(metadata.getAsJsonArray("order").get(0), boundary.get("stepClass"));
            assertEquals("InputAlias", boundary.get("inputTypeId").getAsString());
            assertEquals("OutputAlias", boundary.get("outputTypeId").getAsString());
        }
    }

    @Test
    void repeatedNestedCallsitesRetainTheirExactExpandedChildClassesAndAliases() throws Exception {
        Path yaml = directory.resolve("pipeline.yaml");
        Files.writeString(yaml, """
            version: 3
            appName: Repeated linked definitions
            basePackage: proof
            transport: LOCAL
            contract: { input: Input, output: Result }
            types:
              Value: { java: proof.Types.Value, fields: [[value, string]] }
              ValueAlias: { alias: Value }
              Pending: { java: proof.Types.Pending, fields: [[value, string]] }
              PendingAlias: { alias: Pending }
              Ready: { java: proof.Types.Ready, fields: [[value, string]] }
              ReadyAlias: { alias: Ready }
              Input: { java: proof.Types.Input, fields: [[value, string]] }
              Middle: { java: proof.Types.Middle, fields: [[value, string]] }
              Result: { java: proof.Types.Result, fields: [[value, string]] }
            pipelines:
              leaf:
                input: ValueAlias
                output: ReadyAlias
                steps:
                  - name: Prepare
                    service: proof.Types.Prepare
                    cardinality: ONE_TO_ONE
                    input: ValueAlias
                    output: ReadyAlias
                    await:
                      operationOutput: { type: PendingAlias }
                      timeout: PT5M
                      correlation: { strategy: signedResumeToken }
                      transport: { type: interaction-api }
              parentOne:
                input: Input
                output: Middle
                steps:
                  - name: First prepare
                    service: proof.Types.FirstPrepare
                    cardinality: ONE_TO_ONE
                    input: Input
                    output: ValueAlias
                  - name: Inside
                    pipeline: leaf
                    cardinality: ONE_TO_ONE
                    input: ValueAlias
                    output: ReadyAlias
                    java: { input: proof.Types.Value, output: proof.Types.Ready }
                  - name: First finish
                    service: proof.Types.FirstFinish
                    cardinality: ONE_TO_ONE
                    input: ReadyAlias
                    output: Middle
              parentTwo:
                input: Middle
                output: Result
                steps:
                  - name: Second prepare
                    service: proof.Types.SecondPrepare
                    cardinality: ONE_TO_ONE
                    input: Middle
                    output: ValueAlias
                  - name: Inside
                    pipeline: leaf
                    cardinality: ONE_TO_ONE
                    input: ValueAlias
                    output: ReadyAlias
                    java: { input: proof.Types.Value, output: proof.Types.Ready }
                  - name: Second finish
                    service: proof.Types.SecondFinish
                    cardinality: ONE_TO_ONE
                    input: ReadyAlias
                    output: Result
            steps:
              - name: First
                pipeline: parentOne
                cardinality: ONE_TO_ONE
                input: Input
                output: Middle
                java: { input: proof.Types.Input, output: proof.Types.Middle }
              - name: Second
                pipeline: parentTwo
                cardinality: ONE_TO_ONE
                input: Middle
                output: Result
                java: { input: proof.Types.Middle, output: proof.Types.Result }
            layout: monolith
            aspects:
              persistence:
                enabled: true
                scope: GLOBAL
                position: AFTER_STEP
                config: { pluginImplementationClass: proof.Types.Persistence }
            """);
        String source = """
            package proof;
            public class Types {
              public record Value(String value) {}
              public record Pending(String value) {}
              public record Ready(String value) {}
              public record Input(String value) {}
              public record Middle(String value) {}
              public record Result(String value) {}
              public static class FirstPrepare implements org.pipelineframework.service.ReactiveService<Input, Value> {
                public io.smallrye.mutiny.Uni<Value> process(Input input) { return null; }
              }
              public static class FirstFinish implements org.pipelineframework.service.ReactiveService<Ready, Middle> {
                public io.smallrye.mutiny.Uni<Middle> process(Ready input) { return null; }
              }
              public static class SecondPrepare implements org.pipelineframework.service.ReactiveService<Middle, Value> {
                public io.smallrye.mutiny.Uni<Value> process(Middle input) { return null; }
              }
              public static class SecondFinish implements org.pipelineframework.service.ReactiveService<Ready, Result> {
                public io.smallrye.mutiny.Uni<Result> process(Ready input) { return null; }
              }
              public static class Prepare implements org.pipelineframework.service.ReactiveService<Value, Pending> {
                public io.smallrye.mutiny.Uni<Pending> process(Value input) {
                  return io.smallrye.mutiny.Uni.createFrom().item(new Pending(input.value()));
                }
              }
              public static class Persistence {}
            }
            """;
        Path generated = directory.resolve("generated");
        var phases = new ArrayList<PipelineCompilationPhase>(List.of(
            new PipelineDiscoveryPhase(), new PipelineBranchPlanningPhase(), new ModelExtractionPhase(),
            new PipelineSemanticAnalysisPhase(), new PipelineTargetResolutionPhase()));
        phases.add(new PipelineCompilationPhase() {
            @Override public String name() { return "Linked physical boundary proof"; }
            @Override public void execute(PipelineCompilationContext ctx) throws Exception {
                new LocalPipelineInvocationRenderer().render(ctx, generated);
                recordAwaitDescriptors(ctx, generated);
                new PipelineOrderMetadataGenerator(ctx.getProcessingEnv()).writeOrderMetadata(ctx);
                new org.pipelineframework.processor.util.PipelineContractMetadataGenerator(ctx.getProcessingEnv()).writePipelineContract(ctx);
            }
        });
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        assertTrue(compile(yaml, source, phases, diagnostics), diagnostics.getDiagnostics().toString());
        var metadata = JsonParser.parseString(Files.readString(directory.resolve("classes/META-INF/pipeline/order.json")))
            .getAsJsonObject();
        var definitions = metadata.getAsJsonArray("definitionBoundaries").asList().stream()
            .map(value -> value.getAsJsonObject()).collect(java.util.stream.Collectors.toMap(
                value -> value.get("invocationClass").getAsString(), value -> value));
        assertEquals(4, definitions.size());
        var root = metadata.getAsJsonArray("stepBoundaries");
        assertEquals(2, root.size());
        var leafClasses = new java.util.HashSet<String>();
        for (int index = 0; index < root.size(); index++) {
            var middle = definitions.get(root.get(index).getAsJsonObject().get("stepClass").getAsString());
            assertEquals(index == 0 ? "parentOne" : "parentTwo", middle.get("definitionId").getAsString());
            assertEquals(index == 0 ? "First" : "Second", middle.get("callsiteId").getAsString());
            String leafClass = middle.getAsJsonArray("stepBoundaries").asList().stream()
                .map(value -> value.getAsJsonObject().get("stepClass").getAsString())
                .filter(value -> value.contains(".PipelineInvocation_")).findFirst().orElseThrow();
            assertTrue(leafClasses.add(leafClass), "Different parent callsites must select different linked child classes");
            var leaf = definitions.get(leafClass);
            assertEquals("leaf", leaf.get("definitionId").getAsString());
            assertEquals("Inside", leaf.get("callsiteId").getAsString());
            var boundaries = leaf.getAsJsonArray("stepBoundaries").asList().stream().map(value -> value.getAsJsonObject()).toList();
            assertEquals(List.of("ValueAlias", "PendingAlias", "PendingAlias"),
                boundaries.stream().map(value -> value.get("inputTypeId").getAsString()).toList());
            assertEquals(List.of("PendingAlias", "PendingAlias", "ReadyAlias"),
                boundaries.stream().map(value -> value.get("outputTypeId").getAsString()).toList());
            String linkedSource = Files.readString(generated.resolve(leafClass.replace('.', '/') + ".java"));
            for (int child = 0; child < boundaries.size(); child++) {
                String className = boundaries.get(child).get("stepClass").getAsString();
                assertTrue(linkedSource.contains(className.substring(className.lastIndexOf('.') + 1) + " child" + child), linkedSource);
            }
        }
        var compiledContract = JsonParser.parseString(
            Files.readString(directory.resolve("classes/META-INF/pipeline/pipeline-contract.json"))).getAsJsonObject();
        var compiledPositions = compiledContract.getAsJsonArray("steps").asList().stream()
            .map(value -> value.getAsJsonObject().getAsJsonObject("deferredCompletion"))
            .filter(java.util.Objects::nonNull)
            .map(value -> value.getAsJsonObject("runtimeDescriptor"))
            .filter(java.util.Objects::nonNull)
            .filter(value -> value.has("positions"))
            .flatMap(value -> value.getAsJsonArray("positions").asList().stream())
            .map(value -> value.getAsJsonObject())
            .filter(value -> value.has("invocationClass"))
            .map(value -> value.get("invocationClass").getAsString())
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(leafClasses, compiledPositions);

    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsRootAndChildOperationsBeforeTheirObserversAndAwait(boolean persistence) throws Exception {
        Path yaml = directory.resolve("pipeline.yaml");
        Files.writeString(yaml, """
            version: 3
            appName: Ordinary Await composition
            basePackage: proof
            transport: LOCAL
            contract: { input: Input, output: Result }
            types:
              Input: { java: proof.Types.Input, fields: [[value, string]] }
              PendingChild: { java: proof.Types.PendingChild, fields: [[value, string]] }
              PendingChildAlias: { alias: PendingChild }
              Ready: { java: proof.Types.Ready, fields: [[value, string]] }
              PendingRoot: { java: proof.Types.PendingRoot, fields: [[value, string]] }
              Result: { java: proof.Types.Result, fields: [[value, string]] }
            pipelines:
              prepare:
                input: Input
                output: Ready
                steps:
                  - name: Prepare
                    service: proof.Types.Prepare
                    cardinality: ONE_TO_ONE
                    input: Input
                    accepts: [Input]
                    output: Ready
                    await:
                      operationOutput: { type: PendingChildAlias }
                      timeout: PT5M
                      correlation: { strategy: signedResumeToken }
                      transport: { type: interaction-api }
            steps:
              - name: Run preparation
                pipeline: prepare
                cardinality: ONE_TO_ONE
                input: Input
                output: Ready
                java: { input: proof.Types.Input, output: proof.Types.Ready }
              - name: Freeze
                service: proof.Types.Freeze
                cardinality: ONE_TO_ONE
                input: Ready
                accepts: [Ready]
                output: Result
                await:
                  operationOutput: { type: PendingRoot }
                  timeout: PT5M
                  correlation: { strategy: signedResumeToken }
                  transport: { type: interaction-api }
            layout: monolith
            """ + (persistence ? """
            aspects:
              persistence:
                enabled: true
                scope: STEPS
                position: AFTER_STEP
                config:
                  targetSteps: [Prepare, Freeze]
                  pluginImplementationClass: proof.Types.Persistence
            """ : ""));
        String source = """
            package proof;
            public class Types {
              public record Input(String value) {}
              public record PendingChild(String value) {}
              public record Ready(String value) {}
              public record PendingRoot(String value) {}
              public record Result(String value) {}
              public static class Prepare implements org.pipelineframework.service.ReactiveService<Input, PendingChild> {
                public io.smallrye.mutiny.Uni<PendingChild> process(Input input) {
                  return io.smallrye.mutiny.Uni.createFrom().item(new PendingChild(input.value()));
                }
              }
              public static class Freeze implements org.pipelineframework.service.ReactiveService<Ready, PendingRoot> {
                public io.smallrye.mutiny.Uni<PendingRoot> process(Ready input) {
                  return io.smallrye.mutiny.Uni.createFrom().item(new PendingRoot(input.value()));
                }
              }
              public static class Persistence {}
            }
            """;
        Path generated = directory.resolve("generated");
        var phases = new ArrayList<PipelineCompilationPhase>(List.of(
            new PipelineDiscoveryPhase(), new PipelineBranchPlanningPhase(), new ModelExtractionPhase(),
            new PipelineSemanticAnalysisPhase(), new PipelineTargetResolutionPhase()));
        phases.add(new PipelineCompilationPhase() {
            @Override public String name() { return "Local execution lowering"; }
            @Override public void execute(PipelineCompilationContext ctx) throws Exception {
                new LocalPipelineInvocationRenderer().render(ctx, generated);
                recordAwaitDescriptors(ctx, generated);
                new PipelineOrderMetadataGenerator(ctx.getProcessingEnv()).writeOrderMetadata(ctx);
                new PipelineBranchingMetadataGenerator(ctx.getProcessingEnv()).writeBranchingMetadata(ctx);
            }
        });
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        assertTrue(compile(yaml, source, phases, diagnostics), diagnostics.getDiagnostics().toString());
        Path classes = directory.resolve("classes");
        var order = JsonParser.parseString(Files.readString(classes.resolve("META-INF/pipeline/order.json")))
            .getAsJsonObject().getAsJsonArray("order").asList().stream().map(value -> value.getAsString()).toList();
        var metadata = JsonParser.parseString(Files.readString(classes.resolve("META-INF/pipeline/order.json"))).getAsJsonObject();
        assertEquals(1, metadata.get("boundarySchemaVersion").getAsInt());
        var boundaries = metadata.getAsJsonArray("stepBoundaries").asList().stream().map(value -> value.getAsJsonObject()).toList();
        assertEquals(order, boundaries.stream().map(value -> value.get("stepClass").getAsString()).toList());
        assertEquals(persistence ? List.of("Input", "Ready", "PendingRoot", "PendingRoot")
            : List.of("Input", "Ready", "PendingRoot"),
            boundaries.stream().map(value -> value.get("inputTypeId").getAsString()).toList());
        assertEquals(persistence ? List.of("Ready", "PendingRoot", "PendingRoot", "Result")
            : List.of("Ready", "PendingRoot", "Result"),
            boundaries.stream().map(value -> value.get("outputTypeId").getAsString()).toList());
        var definitions = metadata.getAsJsonArray("definitionBoundaries");
        assertEquals(1, definitions.size());
        var definition = definitions.get(0).getAsJsonObject();
        assertEquals("prepare", definition.get("definitionId").getAsString());
        assertEquals("Run preparation", definition.get("callsiteId").getAsString());
        assertEquals(order.getFirst(), definition.get("invocationClass").getAsString());
        var nested = definition.getAsJsonArray("stepBoundaries").asList().stream().map(value -> value.getAsJsonObject()).toList();
        assertEquals(persistence ? List.of("Input", "PendingChildAlias", "PendingChildAlias")
            : List.of("Input", "PendingChildAlias"),
            nested.stream().map(value -> value.get("inputTypeId").getAsString()).toList());
        assertEquals(persistence ? List.of("PendingChildAlias", "PendingChildAlias", "Ready")
            : List.of("PendingChildAlias", "Ready"),
            nested.stream().map(value -> value.get("outputTypeId").getAsString()).toList());
        assertEquals(persistence ? List.of("proof.pipeline.ProcessPrepareLocalClientStep",
            "proof.pipeline.PersistencePendingChildSideEffectLocalClientStep", "proof.pipeline.ProcessPrepareDeferredCompletionStep")
            : List.of("proof.pipeline.ProcessPrepareLocalClientStep", "proof.pipeline.ProcessPrepareDeferredCompletionStep"),
            nested.stream().map(value -> value.get("stepClass").getAsString()).toList());
        Path invocation;
        try (var files = Files.walk(generated)) {
            invocation = files.filter(path -> path.getFileName().toString().startsWith("PipelineInvocation_")).findFirst().orElseThrow();
        }
        String child = Files.readString(invocation);
        List<String> expectedRoot = new ArrayList<>(List.of("proof.pipeline." + invocation.getFileName().toString()
            .replace(".java", ""), "proof.pipeline.ProcessFreezeLocalClientStep"));
        if (persistence) expectedRoot.add("proof.pipeline.PersistencePendingRootSideEffectLocalClientStep");
        expectedRoot.add("proof.pipeline.ProcessFreezeDeferredCompletionStep");
        var branching = JsonParser.parseString(Files.readString(classes.resolve("META-INF/pipeline/branching.json")))
            .getAsJsonObject().getAsJsonArray("steps").asList().stream().map(value -> value.getAsJsonObject())
            .filter(step -> "prepare".equals(step.get("definitionId").getAsString()))
            .filter(step -> !step.get("afterStepObserver").getAsBoolean()).toList();
        assertAll(
            () -> assertEquals(expectedRoot, order),
            () -> {
                assertEquals(List.of("proof.pipeline.ProcessPrepareLocalClientStep",
                    "proof.pipeline.ProcessPrepareDeferredCompletionStep"), branching.stream()
                    .map(step -> step.get("runtimeStepClass").getAsString()).toList());
                assertEquals(List.of("proof.Types$Input", "proof.Types$PendingChild"), branching.stream()
                    .map(step -> step.get("inputRuntimeClass").getAsString()).toList());
            },
            () -> {
                assertTrue(child.contains("StepOneToOne<Input, Ready>"), child);
                int operation = child.indexOf("ProcessPrepareLocalClientStep child0");
                int completion = child.indexOf("ProcessPrepareDeferredCompletionStep child" + (persistence ? 2 : 1));
                assertTrue(operation >= 0 && completion > operation, child);
                if (persistence) {
                    int observer = child.indexOf("PersistencePendingChildSideEffectLocalClientStep child1");
                    assertTrue(observer > operation && observer < completion, child);
                }
                assertTrue(child.contains("java.util.List.of(child0, child1" + (persistence ? ", child2" : "") + ")"), child);
            });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesExactAwaitAliasesAndRejectsAmbiguousJavaBindings(boolean duplicateBinding) throws Exception {
        Path yaml = directory.resolve("pipeline.yaml");
        Files.writeString(yaml, """
            version: 3
            appName: Await alias identity
            basePackage: proof
            transport: LOCAL
            contract: { input: Input, output: Result }
            types:
              Input: { java: proof.Types.Input, fields: [[value, string]] }
              PendingFirst: { java: proof.Types.PendingOne, fields: [[value, string]] }
              PendingSecond: { java: proof.Types.PendingTwo, fields: [[value, string]] }
              PendingOne: { java: proof.Types.Decoy, fields: [[value, string]] }
              Ready: { java: proof.Types.Ready, fields: [[value, string]] }
              Result: { java: proof.Types.Result, fields: [[value, string]] }
            steps:
              - name: First
                service: proof.Types.First
                cardinality: ONE_TO_ONE
                input: Input
                output: Ready
                await:
                  operationOutput: { type: PendingFirst }
                  timeout: PT1M
                  correlation: { strategy: interactionId }
                  transport: { type: interaction-api }
              - name: Second
                service: proof.Types.Second
                cardinality: ONE_TO_ONE
                input: Ready
                output: Result
                await:
                  operationOutput: { type: PendingSecond }
                  timeout: PT1M
                  correlation: { strategy: interactionId }
                  transport: { type: interaction-api }
            layout: monolith
            """);
        if (duplicateBinding) {
            Files.writeString(yaml, Files.readString(yaml).replace("java: proof.Types.PendingTwo", "java: proof.Types.PendingOne"));
        }
        String source = """
            package proof;
            public class Types {
              public record Input(String value) {}
              public record PendingOne(String value) {}
              public record PendingTwo(String value) {}
              public record Decoy(String value) {}
              public record Ready(String value) {}
              public record Result(String value) {}
              public static class First implements org.pipelineframework.service.ReactiveService<Input, PendingOne> {
                public io.smallrye.mutiny.Uni<PendingOne> process(Input value) { return io.smallrye.mutiny.Uni.createFrom().item(new PendingOne(value.value())); }
              }
              public static class Second implements org.pipelineframework.service.ReactiveService<Ready, PendingTwo> {
                public io.smallrye.mutiny.Uni<PendingTwo> process(Ready value) { return io.smallrye.mutiny.Uni.createFrom().item(new PendingTwo(value.value())); }
              }
            }
            """;
        var phases = new ArrayList<PipelineCompilationPhase>(List.of(new PipelineDiscoveryPhase(),
            new PipelineBranchPlanningPhase(), new ModelExtractionPhase(), new PipelineSemanticAnalysisPhase(),
            new PipelineTargetResolutionPhase()));
        phases.add(new PipelineCompilationPhase() {
            @Override public String name() { return "Verify canonical Await identity"; }
            @Override public void execute(PipelineCompilationContext ctx) throws Exception {
                var models = ctx.getStepModels().stream().filter(model -> model.deferredCompletionSelection().isPresent()).toList();
                assertEquals(2, models.size());
                for (var model : models) {
                    String alias = model.serviceName().contains("First") ? "PendingFirst" : "PendingSecond";
                    String javaName = model.serviceName().contains("First") ? "proof.Types.PendingOne" : "proof.Types.PendingTwo";
                    assertEquals(alias, model.outputMapping().canonicalTypeName().orElseThrow());
                    assertEquals(javaName, model.outputMapping().domainType().toString());
                    var rendering = org.pipelineframework.processor.renderer.Jsr269GenerationContext.create(
                        ctx.getProcessingEnv(), directory.resolve("generated"),
                        org.pipelineframework.processor.ir.DeploymentRole.ORCHESTRATOR_CLIENT,
                        java.util.Set.of(), null, null, org.pipelineframework.processor.ir.PipelineTransport.REST,
                        "proof", null, false, java.util.Optional.of(ctx.getPipelineTemplateConfig().typeModel()));
                    var facts = new org.pipelineframework.processor.renderer.DeferredCompletionStepRenderer()
                        .renderWithDescriptorMetadata(model, rendering, null);
                    assertEquals(alias, facts.get("canonicalInputTypeId"));
                    assertEquals("proof.dto." + alias + "Dto", facts.get("transportInputTypeId"));
                    assertEquals(javaName, facts.get("inputTypeId"));
                }
            }
        });
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        boolean success = compile(yaml, source, phases, diagnostics);
        if (duplicateBinding) {
            assertFalse(success);
            assertTrue(diagnostics.getDiagnostics().stream().anyMatch(diagnostic -> diagnostic.getMessage(null)
                .contains("cannot be bound to both 'PendingFirst' and 'PendingSecond'")), diagnostics.getDiagnostics().toString());
        } else {
            assertTrue(success, diagnostics.getDiagnostics().toString());
        }
    }

    private static void recordAwaitDescriptors(PipelineCompilationContext ctx, Path generated) throws Exception {
        for (var model : ctx.getStepModels().stream()
            .filter(candidate -> candidate.deferredCompletionSelection().isPresent()).toList()) {
            var rendering = org.pipelineframework.processor.renderer.Jsr269GenerationContext.create(
                ctx.getProcessingEnv(), generated, model.deploymentRole(), java.util.Set.of(), null, null,
                org.pipelineframework.processor.ir.PipelineTransport.LOCAL, "proof", null, false,
                java.util.Optional.of(ctx.getPipelineTemplateConfig().typeModel()));
            ctx.recordAwaitRuntimeDescriptor(model,
                new org.pipelineframework.processor.renderer.DeferredCompletionStepRenderer()
                    .renderWithDescriptorMetadata(model, rendering, null));
        }
    }

    private boolean compile(Path yaml, String source, List<PipelineCompilationPhase> phases,
            DiagnosticCollector<JavaFileObject> diagnostics) throws Exception {
        var unit = new SimpleJavaFileObject(URI.create("string:///proof/Types.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, manager, diagnostics, List.of(
                "-proc:only", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(),
                "-Apipeline.config=" + yaml), null, List.of(unit));
            task.setProcessors(List.of(new PipelineStepProcessor(new PipelineCompiler(phases))));
            return task.call();
        }
    }
}
