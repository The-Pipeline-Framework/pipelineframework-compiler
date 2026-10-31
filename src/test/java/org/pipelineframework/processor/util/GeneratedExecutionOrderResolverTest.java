package org.pipelineframework.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;
import org.pipelineframework.processor.Jsr269SourceInventory;
import org.pipelineframework.processor.AspectExpansionProcessor;
import org.pipelineframework.processor.ResolvedStep;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.composition.PipelineReference;
import org.pipelineframework.processor.ir.AspectPosition;
import org.pipelineframework.processor.ir.AspectScope;
import org.pipelineframework.processor.ir.PipelineAspectModel;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

class GeneratedExecutionOrderResolverTest {

    @Test
    void expansionKeepsSharedObserverInBothDefinitionOrders() {
        PipelineReference root = new PipelineReference("$root");
        PipelineReference child = new PipelineReference("child");
        PipelineStepModel rootStep = model("Root", "RootService", false, root, null);
        PipelineStepModel childStep = model("Child", "ChildService", false, child, null);
        for (AspectScope scope : AspectScope.values()) {
            for (AspectPosition position : AspectPosition.values()) {
                var config = new java.util.HashMap<String, Object>();
                config.put("pluginImplementationClass", "com.example.PersistenceService");
                if (scope == AspectScope.STEPS) {
                    config.put("targetSteps", List.of("Root", "Child"));
                }
                var aspect = new PipelineAspectModel("persistence", scope, position, config);
                var expanded = new AspectExpansionProcessor().expandAspects(
                    List.of(new ResolvedStep(rootStep, null, null), new ResolvedStep(childStep, null, null)),
                    List.of(aspect));
                PipelineCompilationContext ctx = new PipelineCompilationContext(null, Jsr269SourceInventory.empty());
                ctx.setTransportMode(PipelineTransport.LOCAL);
                ctx.setStepModels(expanded.stream().map(ResolvedStep::model)
                    .map(model -> model.withDeploymentRole(DeploymentRole.ORCHESTRATOR_CLIENT)).toList());
                var resolver = new GeneratedExecutionOrderResolver();
                String observer = "com.example.pipeline.PersistenceValueSideEffectLocalClientStep";
                String rootClass = "com.example.pipeline.RootLocalClientStep";
                String childClass = "com.example.pipeline.ChildLocalClientStep";
                assertEquals(position == AspectPosition.BEFORE_STEP ? List.of(observer, rootClass)
                        : List.of(rootClass, observer),
                    resolver.weave(ctx, root, List.of(rootClass), true));
                assertEquals(position == AspectPosition.BEFORE_STEP ? List.of(observer, childClass)
                        : List.of(childClass, observer),
                    resolver.weave(ctx, child, List.of(childClass), true));
            }
        }
    }

    @Test
    void weavesOnlySideEffectsOwnedByTheSelectedDefinition() {
        PipelineReference definition = new PipelineReference("deployment-lifecycle");
        PipelineCompilationContext ctx = new PipelineCompilationContext(null, Jsr269SourceInventory.empty());
        ctx.setTransportMode(PipelineTransport.LOCAL);
        ctx.setStepModels(List.of(
            model("Approve", "ApproveService", false, definition, null),
            model("ObserveApproved", "PersistenceApprovedSideEffectService", true, definition,
                AspectPosition.AFTER_STEP),
            model("Reject", "RejectService", false, definition, null),
            model("ObserveRejected", "PersistenceRejectedSideEffectService", true, definition,
                AspectPosition.AFTER_STEP),
            model("ObserveRoot", "PersistenceRootSideEffectService", true,
                new PipelineReference("$root"), AspectPosition.AFTER_STEP)));

        List<String> order = new GeneratedExecutionOrderResolver().weave(
            ctx,
            definition,
            List.of(
                "com.example.pipeline.ApproveLocalClientStep",
                "com.example.pipeline.RejectLocalClientStep"),
            true);

        assertEquals(List.of(
            "com.example.pipeline.ApproveLocalClientStep",
            "com.example.pipeline.PersistenceApprovedSideEffectLocalClientStep",
            "com.example.pipeline.RejectLocalClientStep",
            "com.example.pipeline.PersistenceRejectedSideEffectLocalClientStep"), order);
    }

    private PipelineStepModel model(
        String serviceName,
        String generatedName,
        boolean sideEffect,
        PipelineReference definition,
        AspectPosition position
    ) {
        PipelineStepModel.Builder builder = new PipelineStepModel.Builder()
            .serviceName(serviceName)
            .generatedName(generatedName)
            .servicePackage("com.example")
            .serviceClassName(ClassName.get("com.example", generatedName))
            .inputMapping(new TypeMapping(ClassName.get("com.example", "Value"), null, false))
            .outputMapping(new TypeMapping(ClassName.get("com.example", "Value"), null, false))
            .streamingShape(StreamingShape.UNARY_UNARY)
            .enabledTargets(Set.of(GenerationTarget.LOCAL_CLIENT_STEP))
            .executionMode(ExecutionMode.DEFAULT)
            .deploymentRole(DeploymentRole.ORCHESTRATOR_CLIENT)
            .sideEffect(sideEffect)
            .definition(definition);
        if (position != null) {
            builder.aspectPosition(position);
        }
        return builder.build();
    }
}
