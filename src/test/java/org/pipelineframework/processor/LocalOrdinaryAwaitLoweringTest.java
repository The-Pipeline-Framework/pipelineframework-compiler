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
                      operationOutput: { type: PendingChild }
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
        var unit = new SimpleJavaFileObject(URI.create("string:///proof/Types.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        Path generated = directory.resolve("generated");
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var phases = new ArrayList<PipelineCompilationPhase>(List.of(
            new PipelineDiscoveryPhase(), new PipelineBranchPlanningPhase(), new ModelExtractionPhase(),
            new PipelineSemanticAnalysisPhase(), new PipelineTargetResolutionPhase()));
        phases.add(new PipelineCompilationPhase() {
            @Override public String name() { return "Local execution lowering"; }
            @Override public void execute(PipelineCompilationContext ctx) throws Exception {
                new LocalPipelineInvocationRenderer().render(ctx, generated);
                new PipelineOrderMetadataGenerator(ctx.getProcessingEnv()).writeOrderMetadata(ctx);
                new PipelineBranchingMetadataGenerator(ctx.getProcessingEnv()).writeBranchingMetadata(ctx);
            }
        });
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, manager, diagnostics, List.of(
                "-proc:only", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(),
                "-Apipeline.config=" + yaml), null, List.of(unit));
            task.setProcessors(List.of(new PipelineStepProcessor(new PipelineCompiler(phases))));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        var order = JsonParser.parseString(Files.readString(classes.resolve("META-INF/pipeline/order.json")))
            .getAsJsonObject().getAsJsonArray("order").asList().stream().map(value -> value.getAsString()).toList();
        Path invocation;
        try (var files = Files.walk(generated)) {
            invocation = files.filter(path -> path.toString().endsWith(".java")).findFirst().orElseThrow();
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
}
