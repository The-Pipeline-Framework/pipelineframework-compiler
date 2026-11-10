package org.pipelineframework.processor.renderer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;

import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipelineframework.config.CardinalitySemantics;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.Jsr269SourceInventory;
import org.pipelineframework.processor.composition.PipelineDefinition;
import org.pipelineframework.processor.composition.PipelineDefinitionStep;
import org.pipelineframework.processor.composition.PipelineReference;
import org.pipelineframework.processor.composition.ResolvedPipelineDefinitionGraph;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.ir.StepDefinition;
import org.pipelineframework.processor.ir.StepKind;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

import org.junit.jupiter.api.Test;

import org.pipelineframework.processor.ir.ConnectorOperationSelection;
import org.pipelineframework.processor.ir.DynamicOperationSelection;
import org.pipelineframework.processor.ir.PipelineStepModel;

class LocalPipelineInvocationRendererTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsQueryAndOrdinaryRootStepsDistinctDespiteSharedGeneratedNames(boolean ordinaryModelFirst) throws Exception {
        var root = new PipelineReference("$root");
        var definition = new PipelineDefinition(root, "Input", "Result", List.of(
            PipelineDefinitionStep.direct("foo", "Input", "Ready", CardinalitySemantics.ONE_TO_ONE),
            PipelineDefinitionStep.direct("Process foo", "Ready", "Result", CardinalitySemantics.ONE_TO_ONE)));
        var ctx = new PipelineCompilationContext(null, Jsr269SourceInventory.empty());
        ctx.setTransportMode(PipelineTransport.LOCAL);
        var config = mock(PipelineTemplateConfig.class);
        when(config.basePackage()).thenReturn("proof");
        ctx.setPipelineTemplateConfig(config);
        ctx.setResolvedPipelineDefinitionGraph(new ResolvedPipelineDefinitionGraph(definition,
            Map.of(root, definition), CardinalitySemantics.ONE_TO_ONE, List.of(), List.of()));
        var queryStep = mock(StepDefinition.class);
        when(queryStep.name()).thenReturn("foo");
        when(queryStep.kind()).thenReturn(StepKind.QUERY);
        var ordinaryStep = mock(StepDefinition.class);
        when(ordinaryStep.name()).thenReturn("Process foo");
        when(ordinaryStep.kind()).thenReturn(StepKind.INTERNAL);
        ctx.setStepDefinitions(List.of(queryStep, ordinaryStep));
        var ordinary = new PipelineStepModel.Builder()
            .serviceName("ProcessFooService").generatedName("ProcessFooService").servicePackage("proof")
            .serviceClassName(ClassName.get("proof", "Foo"))
            .inputMapping(TypeMapping.withoutMapper(ClassName.get("proof", "Ready")))
            .outputMapping(TypeMapping.withoutMapper(ClassName.get("proof", "Result")))
            .streamingShape(StreamingShape.UNARY_UNARY).executionMode(ExecutionMode.DEFAULT)
            .deploymentRole(DeploymentRole.PIPELINE_SERVER)
            .enabledTargets(Set.of(GenerationTarget.GRPC_SERVICE_SIDE_EFFECT_ONLY)).build();
        var selection = mock(ConnectorOperationSelection.class);
        when(selection.authoredStepName()).thenReturn("foo");
        var query = ordinary.toBuilder()
            .serviceClassName(ClassName.get("org.pipelineframework.query", "QueryStepDescriptor"))
            .deploymentRole(DeploymentRole.ORCHESTRATOR_CLIENT)
            .connectorOperationSelection(selection)
            .enabledTargets(Set.of(GenerationTarget.QUERY_CLIENT_STEP)).build();
        ctx.setStepModels(ordinaryModelFirst ? List.of(ordinary, query) : List.of(query, ordinary));

        new LocalPipelineInvocationRenderer().render(ctx, directory);

        org.junit.jupiter.api.Assertions.assertEquals(List.of("proof.pipeline.ProcessFooQueryClientStep", "proof.Foo"),
            ctx.getGeneratedRootPipelineStepClasses());
    }

    @Test
    void rejectsOrdinaryModelsWithAnAmbiguousGeneratedName() {
        PipelineStepModel first = mock(PipelineStepModel.class);
        when(first.serviceName()).thenReturn("ProcessFooService");
        when(first.serviceClassName()).thenReturn(ClassName.get("proof", "FirstFoo"));
        PipelineStepModel second = mock(PipelineStepModel.class);
        when(second.serviceName()).thenReturn("ProcessFooService");
        when(second.serviceClassName()).thenReturn(ClassName.get("proof", "SecondFoo"));

        for (String name : List.of("Foo", "Process foo")) {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> LocalPipelineInvocationRenderer.findAuthoredStepModel(List.of(first, second), name));
            assertTrue(failure.getMessage().contains("Multiple generated step models match authored step '" + name + "'"));
            assertTrue(failure.getMessage().contains("proof.FirstFoo, proof.SecondFoo"));
        }
    }

    @Test void matchesImportedOperationModelsByAuthoredIdentity() {
        PipelineStepModel model = mock(PipelineStepModel.class);
        ConnectorOperationSelection selection = mock(ConnectorOperationSelection.class);
        when(selection.authoredStepName()).thenReturn("Execute GraphQL Query");
        when(model.connectorOperationSelection()).thenReturn(Optional.of(selection));
        when(model.serviceName()).thenReturn("ProcessExecuteGraphqlQueryBlock8f1198adb48ded87Service");

        assertTrue(LocalPipelineInvocationRenderer.matchesAuthoredStep(model, "Execute GraphQL Query"));
        assertFalse(LocalPipelineInvocationRenderer.matchesAuthoredStep(model, "Execute GraphQL Mutation"));
    }

    @Test void sharesTheCompilerNamingPolicyForOrdinaryChildSteps() {
        PipelineStepModel model = mock(PipelineStepModel.class);
        when(model.connectorOperationSelection()).thenReturn(Optional.empty());
        when(model.serviceName()).thenReturn("ProcessCanonicalizeGraphqlQueryService");

        assertTrue(LocalPipelineInvocationRenderer.matchesAuthoredStep(model, "Canonicalize GraphQL Query"));
    }

    @Test void matchesImportedDynamicOperationModelsByQualifiedRuntimeIdentity() {
        PipelineStepModel model = mock(PipelineStepModel.class);
        DynamicOperationSelection selection = mock(DynamicOperationSelection.class);
        when(model.connectorOperationSelection()).thenReturn(Optional.empty());
        when(model.dynamicOperationSelection()).thenReturn(Optional.of(selection));
        when(selection.ownsAuthoredStep("Invoke proposal")).thenReturn(true);
        when(selection.ownsAuthoredStep("Other proposal")).thenReturn(false);

        assertTrue(LocalPipelineInvocationRenderer.matchesAuthoredStep(model, "Invoke proposal"));
        assertFalse(LocalPipelineInvocationRenderer.matchesAuthoredStep(model, "Other proposal"));
    }
}
