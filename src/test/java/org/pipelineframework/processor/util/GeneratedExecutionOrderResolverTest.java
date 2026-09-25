package org.pipelineframework.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;
import org.pipelineframework.processor.Jsr269SourceInventory;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.composition.PipelineReference;
import org.pipelineframework.processor.ir.AspectPosition;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

class GeneratedExecutionOrderResolverTest {

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
