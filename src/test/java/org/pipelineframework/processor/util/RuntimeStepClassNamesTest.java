package org.pipelineframework.processor.util;

import java.util.Optional;
import java.util.Set;

import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeStepClassNamesTest {

    @Test
    void preservesCompilerResolvedRootIdentityAcrossLaterAdapterProjection() {
        PipelineStepModel model = model(Set.of(GenerationTarget.LOCAL_CLIENT_STEP));

        assertEquals(
            "com.example.HandleApprovedService",
            RuntimeStepClassNames.className(
                model, PipelineTransport.LOCAL, false, Optional.of("com.example.HandleApprovedService")));
        assertFalse(RuntimeStepClassNames.usesGeneratedClient(
            model, false, Optional.of("com.example.HandleApprovedService")));
    }

    @Test
    void resolvesAuthoredClassWhenTheStepHasNoGeneratedClient() {
        assertEquals(
            "com.example.HandleApprovedService",
            RuntimeStepClassNames.className(model(Set.of(GenerationTarget.GRPC_SERVICE_SIDE_EFFECT_ONLY)),
                PipelineTransport.LOCAL));
    }

    @Test
    void resolvesGeneratedClientWhenTheSelectedTargetExecutesThroughAnAdapter() {
        assertEquals(
            "com.example.pipeline.HandleApprovedQueryClientStep",
            RuntimeStepClassNames.className(model(Set.of(GenerationTarget.QUERY_CLIENT_STEP)),
                PipelineTransport.LOCAL));
    }

    @Test
    void resolvesGeneratedClientWhenTheExecutionPathRequiresOne() {
        assertEquals(
            "com.example.pipeline.HandleApprovedLocalClientStep",
            RuntimeStepClassNames.className(model(Set.of(GenerationTarget.GRPC_SERVICE_SIDE_EFFECT_ONLY)),
                PipelineTransport.LOCAL, true));
    }

    private PipelineStepModel model(Set<GenerationTarget> targets) {
        return new PipelineStepModel.Builder()
            .serviceName("HandleApproved")
            .generatedName("HandleApprovedService")
            .servicePackage("com.example")
            .serviceClassName(ClassName.get("com.example", "HandleApprovedService"))
            .inputMapping(new TypeMapping(ClassName.get("com.example", "Approved"), null, false))
            .outputMapping(new TypeMapping(ClassName.get("com.example", "ApprovedHandled"), null, false))
            .streamingShape(StreamingShape.UNARY_UNARY)
            .enabledTargets(targets)
            .executionMode(ExecutionMode.DEFAULT)
            .deploymentRole(DeploymentRole.PIPELINE_SERVER)
            .build();
    }
}
