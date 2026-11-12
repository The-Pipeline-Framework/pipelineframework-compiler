package org.pipelineframework.processor.util;

import com.squareup.javapoet.ClassName;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateDialect;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.ir.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PipelineOrderBoundaryMetadataTest {
    @Test void selectedBeforeObserverPreservesOwnerCarrierRatherThanObservedVariant() {
        var ctx = new PipelineCompilationContext(null, null);
        var template = mock(PipelineTemplateConfig.class);
        when(template.dialect()).thenReturn(PipelineTemplateDialect.V3);
        ctx.setPipelineTemplateConfig(template);
        ctx.setTransportMode(PipelineTransport.LOCAL);
        var carrier = ClassName.get("proof", "Carrier");
        var variant = ClassName.get("proof", "Variant");
        var owner = model("Owner", TypeMapping.canonical(carrier, "CarrierAlias"),
            TypeMapping.canonical(carrier, "ResultAlias")).build();
        var observer = model("ObserveSideEffect", TypeMapping.canonical(variant, "VariantAlias"),
            TypeMapping.canonical(variant, "VariantAlias"))
            .sideEffect(true).aspectPosition(AspectPosition.BEFORE_STEP)
            .observationProjection(java.util.Optional.of(new ObservationProjection(carrier, variant, "payload", owner.serviceName()))).build();
        ctx.setStepModels(List.of(observer, owner));
        var order = List.of(ClientStepClassNames.className(observer, PipelineTransport.LOCAL),
            ClientStepClassNames.className(owner, PipelineTransport.LOCAL));
        var boundaries = PipelineOrderBoundaryMetadata.project(ctx, order).orElseThrow();
        assertEquals(order, boundaries.stream().map(PipelineOrderBoundaryMetadata.Boundary::stepClass).toList());
        assertEquals("CarrierAlias", boundaries.getFirst().inputTypeId());
        assertEquals("CarrierAlias", boundaries.getFirst().outputTypeId());
        assertEquals("ResultAlias", boundaries.getLast().outputTypeId());
    }

    @Test void conflictingSemanticBoundariesForOnePhysicalClassAreNotGuessed() {
        var ctx = new PipelineCompilationContext(null, null);
        var template = mock(PipelineTemplateConfig.class);
        when(template.dialect()).thenReturn(PipelineTemplateDialect.V3);
        ctx.setPipelineTemplateConfig(template);
        ctx.setTransportMode(PipelineTransport.LOCAL);
        var javaType = ClassName.get("proof", "Value");
        var first = model("Identity", TypeMapping.canonical(javaType, "FirstAlias"),
            TypeMapping.canonical(javaType, "FirstAlias")).build();
        var second = model("Identity", TypeMapping.canonical(javaType, "SecondAlias"),
            TypeMapping.canonical(javaType, "SecondAlias")).build();
        ctx.setStepModels(List.of(first, second));
        assertTrue(PipelineOrderBoundaryMetadata.project(ctx,
            List.of(ClientStepClassNames.className(first, PipelineTransport.LOCAL))).isEmpty());
    }

    @Test void invocationOrderFactsAreImmutableCompilationScopedAndConflictChecked() {
        var first = new PipelineCompilationContext(null, null);
        var second = new PipelineCompilationContext(null, null);
        var order = new java.util.ArrayList<>(List.of("proof.Operation", "proof.Await"));
        first.recordGeneratedInvocationOrder("proof.Call", "child", "callsite", order);
        order.clear();
        assertEquals(List.of("proof.Operation", "proof.Await"), first.generatedInvocationOrders().getFirst().order());
        assertTrue(second.generatedInvocationOrders().isEmpty());
        assertThrows(IllegalStateException.class, () -> first.recordGeneratedInvocationOrder(
            "proof.Call", "child", "callsite", List.of("proof.Other")));
    }

    private static PipelineStepModel.Builder model(String name, TypeMapping input, TypeMapping output) {
        return new PipelineStepModel.Builder().serviceName(name).generatedName(name).servicePackage("proof")
            .serviceClassName(ClassName.get("proof", name)).inputMapping(input).outputMapping(output)
            .streamingShape(StreamingShape.UNARY_UNARY).enabledTargets(Set.of(GenerationTarget.CLIENT_STEP))
            .executionMode(ExecutionMode.DEFAULT).deploymentRole(DeploymentRole.ORCHESTRATOR_CLIENT).sideEffect(false);
    }
}
