package org.pipelineframework.processor.awaitable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.processor.Jsr269SourceInventory;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.ir.StepDefinition;
import org.pipelineframework.processor.parser.StepDefinitionParser;
import static org.junit.jupiter.api.Assertions.*;

class AwaitBranchSelectionResolverTest {
    @TempDir Path directory;

    @Test
    void selectsTheReviewPayloadAndKeepsTheOtherCanonicalOutcomes() throws Exception {
        var result = compile("{ again: State, answered: Reply, finished: Result }", "Review");
        assertTrue(result.success(), result.diagnostics().toString());
        var branch = result.processor().binding.orElseThrow().branchSelection().orElseThrow();
        assertEquals("Review", branch.requestCanonicalType());
        assertEquals("Reply", branch.completionCanonicalType());
        assertEquals("proof.Types.Before.ReviewRequired", branch.selectedInput().type().canonicalName());
        assertEquals("proof.Types.After.Answered", branch.completionWrapper().orElseThrow().canonicalName());
        assertEquals(2, branch.bypasses().size());
        assertEquals(Set.of("proof.Types.Before.Again", "proof.Types.Before.Finished"),
            branch.bypasses().stream().map(value -> value.input().type().canonicalName()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void rejectsACompletionContractThatDropsAContinuingOutcome() throws Exception {
        var result = compile("{ again: State, answered: Reply }", "Review");
        assertFalse(result.success());
        assertTrue(result.diagnostics().toString().contains("continuing operation variants"));
    }

    @Test
    void rejectsASelectorThatIsNotAnOperationOutputVariant() throws Exception {
        var result = compile("{ again: State, answered: Reply, finished: Result }", "Input");
        assertFalse(result.success());
        assertTrue(result.diagnostics().toString().contains("not an operation-output variant"));
    }

    @Test
    void observesCanonicalReviewAndResultBranchesWithoutChangingTheCarrier() throws Exception {
        var result = compile("{ again: State, answered: Reply, finished: Result }", "Review");
        assertTrue(result.success(), result.diagnostics().toString());
        var observations = result.processor().observations;
        assertEquals(2, observations.size());
        assertEquals(Set.of("Review", "Result"), observations.stream()
            .map(value -> value.mapping().canonicalTypeName().orElseThrow()).collect(java.util.stream.Collectors.toSet()));
        for (var observation : observations) {
            assertEquals("proof.Types.Before", observation.projection().orElseThrow().carrierType().toString());
            assertEquals("EvaluateService", observation.projection().orElseThrow().ownerServiceName());
        }
        assertEquals(Set.of("proof.Types.Before.ReviewRequired", "proof.Types.Before.Finished"), observations.stream()
            .map(value -> value.projection().orElseThrow().variantType().canonicalName()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.processor().unmatchedObservations.isEmpty());
    }

    @Test
    void keepsTypedObserverOccurrencesAcrossDefinitionsAndAcrossProducers() throws Exception {
        var result = compile("{ again: State, answered: Reply, finished: Result }", "Review");
        assertTrue(result.success(), result.diagnostics().toString());
        for (var expanded : java.util.List.of(result.processor().forwardObservers, result.processor().reverseObservers)) {
            assertEquals(6, expanded.size());
            assertEquals(4, expanded.stream().filter(model -> model.definition().logicalId().equals("$root")).count());
            assertEquals(2, expanded.stream().filter(model -> model.definition().logicalId().equals("child")).count());
            assertEquals(2, expanded.stream().map(org.pipelineframework.processor.ir.PipelineStepModel::serviceName).distinct().count());
            assertEquals(4, expanded.stream().map(org.pipelineframework.processor.ir.PipelineStepModel::generatedName).distinct().count());
        }
    }

    @Test
    void rejectsSelectedAwaitAfterAnUpstreamFanOut() throws Exception {
        var result = compile("{ again: State, answered: Reply, finished: Result }", "Review", true);
        assertFalse(result.success());
        assertTrue(result.diagnostics().toString().contains("requires a unary pipeline definition"));
    }

    private Result compile(String finalVariants, String accepted) throws Exception {
        return compile(finalVariants, accepted, false);
    }

    private Result compile(String finalVariants, String accepted, boolean upstreamFanOut) throws Exception {
        String upstream = upstreamFanOut ? """
              - name: Expand
                service: proof.Types.ExpandService
                cardinality: ONE_TO_MANY
                input: Admission
                output: Input
            """ : "";
        Path file = directory.resolve("pipeline.yaml");
        Files.writeString(file, """
            version: 3
            appName: Branch completion
            basePackage: proof
            transport: LOCAL
            contract: { input: %s, output: After }
            types:
              Admission: { java: proof.Types.Admission, fields: [[value, string]] }
              Input: { java: proof.Types.Input, fields: [[value, string]] }
              State: { java: proof.Types.State, fields: [[value, string]] }
              Review: { java: proof.Types.Review, fields: [[value, string]] }
              Reply: { java: proof.Types.Reply, fields: [[value, string]] }
              Result: { java: proof.Types.Result, fields: [[value, string]] }
              Before:
                java: proof.Types.Before
                variants: { again: State, reviewRequired: Review, finished: Result }
              After:
                java: proof.Types.After
                variants: %s
            steps:
            %s  - name: Evaluate
                service: proof.EvaluateService
                cardinality: ONE_TO_ONE
                input: Input
                output: After
                java: { input: proof.Types.Input, output: proof.Types.After }
                await:
                  operationOutput: { type: Before, java: proof.Types.Before }
                  accepts: [%s]
                  output: Reply
                  timeout: PT5M
                  correlation: { strategy: interactionId }
                  transport: { type: interaction-api }
            """.formatted(upstreamFanOut ? "Admission" : "Input", finalVariants, upstream, accepted));
        var config = new PipelineTemplateConfigLoader().load(file);
        var step = new StepDefinitionParser().parseDefinitionCatalog(file).rootSteps().getLast();
        var processor = new Capture(config, step);
        String source = """
            package proof;
            public class Types {
              public record Admission(String value) {}
              public static final class ExpandService {
                public java.util.Iterator<Input> process(Admission admission) {
                  return java.util.List.of(new Input(admission.value())).iterator();
                }
              }
              public record Input(String value) {}
              public record State(String value) {}
              public record Review(String value) {}
              public record Reply(String value) {}
              public record Result(String value) {}
              public sealed interface Before {
                record Again(State value) implements Before {}
                record ReviewRequired(Review value) implements Before {}
                record Finished(Result value) implements Before {}
              }
              public sealed interface After {
                record Again(State value) implements After {}
                record Answered(Reply value) implements After {}
                record Finished(Result value) implements After {}
              }
            }
            """;
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        var unit = new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///proof/Types.java"),
            javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT,
            java.nio.charset.StandardCharsets.UTF_8)) {
            var task = compiler.getTask(new java.io.StringWriter(), manager, diagnostics,
                java.util.List.of("-proc:only", "-d", directory.toString()), java.util.List.of(), java.util.List.of(unit));
            task.setProcessors(java.util.List.of(processor));
            return new Result(task.call(), diagnostics.getDiagnostics(), processor);
        }
    }

    private record Result(boolean success,
        java.util.List<javax.tools.Diagnostic<? extends javax.tools.JavaFileObject>> diagnostics, Capture processor) {}

    private static final class Capture extends AbstractProcessor {
        private final PipelineTemplateConfig config;
        private final StepDefinition step;
        private Optional<AwaitStepTypeBinding> binding = Optional.empty();
        private java.util.List<org.pipelineframework.processor.ir.PipelineStepModel> forwardObservers = java.util.List.of();
        private java.util.List<org.pipelineframework.processor.ir.PipelineStepModel> reverseObservers = java.util.List.of();
        private boolean resolved;
        private java.util.List<org.pipelineframework.processor.AspectObservationResolver.Observation> observations = java.util.List.of();
        private java.util.List<org.pipelineframework.processor.AspectObservationResolver.Observation> unmatchedObservations = java.util.List.of();
        Capture(PipelineTemplateConfig config, StepDefinition step) { this.config = config; this.step = step; }
        @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
        @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
        @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (!resolved && !round.processingOver()) {
                resolved = true;
                var ctx = new PipelineCompilationContext(processingEnv, Jsr269SourceInventory.empty());
                ctx.setPipelineTemplateConfig(config);
                ctx.setTransportMode(PipelineTransport.LOCAL);
                binding = new AwaitStepTypeBindingResolver().resolve(ctx, step);
                if (binding.isPresent()) {
                    var model = new org.pipelineframework.processor.ir.PipelineStepModel.Builder()
                        .serviceName("EvaluateService").generatedName("Evaluate").servicePackage("proof")
                        .serviceClassName(com.squareup.javapoet.ClassName.get("proof", "EvaluateService"))
                        .inputMapping(org.pipelineframework.processor.ir.TypeMapping.canonical(
                            com.squareup.javapoet.ClassName.get("proof", "Types", "Input"), "Input"))
                        .outputMapping(org.pipelineframework.processor.ir.TypeMapping.canonical(
                            com.squareup.javapoet.ClassName.get("proof", "Types", "Before"), "Before"))
                        .streamingShape(org.pipelineframework.processor.ir.StreamingShape.UNARY_UNARY)
                        .executionMode(org.pipelineframework.processor.ir.ExecutionMode.DEFAULT)
                        .enabledTargets(Set.of()).build();
                    var typedAspect = new org.pipelineframework.processor.ir.PipelineAspectModel("persistence",
                        org.pipelineframework.processor.ir.AspectScope.GLOBAL, org.pipelineframework.processor.ir.AspectPosition.AFTER_STEP,
                        java.util.Map.of("targetTypes", java.util.List.of("Review", "Result"), "pluginImplementationClass",
                            "org.pipelineframework.plugin.persistence.PersistenceService"));
                    var other = model.toBuilder().serviceName("OtherService").generatedName("Other").build();
                    var child = model.toBuilder().definition(new org.pipelineframework.processor.composition.PipelineReference("child")).build();
                    var originals = java.util.List.of(model, child, other).stream().map(value ->
                        new org.pipelineframework.processor.ResolvedStep(value,
                            org.mockito.Mockito.mock(org.pipelineframework.processor.ir.GrpcBinding.class),
                            org.mockito.Mockito.mock(org.pipelineframework.processor.ir.RestBinding.class))).toList();
                    var expander = new org.pipelineframework.processor.AspectExpansionProcessor(ctx);
                    forwardObservers = expander.expandAspects(originals, java.util.List.of(typedAspect)).stream()
                        .map(org.pipelineframework.processor.ResolvedStep::model)
                        .filter(org.pipelineframework.processor.ir.PipelineStepModel::sideEffect).toList();
                    reverseObservers = expander.expandAspects(originals.reversed(), java.util.List.of(typedAspect)).stream()
                        .map(org.pipelineframework.processor.ResolvedStep::model)
                        .filter(org.pipelineframework.processor.ir.PipelineStepModel::sideEffect).toList();
                    var resolver = new org.pipelineframework.processor.AspectObservationResolver(ctx);
                    observations = resolver.resolve(model, new org.pipelineframework.processor.ir.PipelineAspectModel(
                        "persistence", org.pipelineframework.processor.ir.AspectScope.GLOBAL,
                        org.pipelineframework.processor.ir.AspectPosition.AFTER_STEP,
                        java.util.Map.of("targetTypes", java.util.List.of("Review", "Result"))));
                    unmatchedObservations = resolver.resolve(model, new org.pipelineframework.processor.ir.PipelineAspectModel(
                        "persistence", org.pipelineframework.processor.ir.AspectScope.GLOBAL,
                        org.pipelineframework.processor.ir.AspectPosition.AFTER_STEP,
                        java.util.Map.of("targetTypes", java.util.List.of("Input"))));
                }
            }
            return false;
        }
    }
}
