package org.pipelineframework.processor.renderer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.OrchestratorBinding;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

class AwsDurableInputDecoderRendererTest {
    @TempDir
    Path output;

    @Test
    void generatesOnlyTheTypedApplicationInputSeam() throws Exception {
        AwsDurableInputDecoderRenderer renderer = new AwsDurableInputDecoderRenderer();

        renderer.render(binding(false), context());

        String source = Files.readString(output.resolve(
            "com/example/orchestrator/service/AwsDurablePipelineInputDecoder.java"));
        assertTrue(source.contains("implements AwsDurableInputDecoder"));
        assertTrue(source.contains("mapper.readValue(inputJson, OrderDto.class)"));
        assertTrue(!source.contains("PipelineControlPlane"));
    }

    @Test
    void rejectsStreamingInputsUntilTheirDurableWireContractIsDefined() {
        AwsDurableInputDecoderRenderer renderer = new AwsDurableInputDecoderRenderer();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> renderer.render(binding(true), context()));
        assertTrue(failure.getMessage().contains("non-streaming pipeline input"));
    }

    private static OrchestratorBinding binding(boolean streaming) {
        var type = com.squareup.javapoet.ClassName.get("com.example.common.domain", "Order");
        var model = new PipelineStepModel(
            "OrchestratorService", "OrchestratorService", "com.example.orchestrator.service",
            com.squareup.javapoet.ClassName.get("com.example.orchestrator.service", "OrchestratorService"),
            TypeMapping.canonical(type, "Order"), TypeMapping.canonical(type, "Order"),
            streaming ? StreamingShape.STREAMING_UNARY : StreamingShape.UNARY_UNARY,
            Set.of(GenerationTarget.GRPC_SERVICE), ExecutionMode.DEFAULT,
            DeploymentRole.ORCHESTRATOR_CLIENT, false, null);
        return new OrchestratorBinding(
            model, "com.example", "REST", "Order", "Order", streaming, false,
            "ProcessOrderService", streaming ? StreamingShape.STREAMING_UNARY : StreamingShape.UNARY_UNARY,
            null, null, null);
    }

    private GenerationContext context() {
        return new GenerationContext(null, null, null, output, DeploymentRole.REST_SERVER,
            Set.of(), null, null);
    }
}
