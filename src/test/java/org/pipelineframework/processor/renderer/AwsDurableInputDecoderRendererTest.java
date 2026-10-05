package org.pipelineframework.processor.renderer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.config.template.PipelineTemplateTypeModel;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.OrchestratorBinding;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.PipelineTransport;
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

    @Test
    void usesCanonicalRestDtoForV3RecordInputs() throws Exception {
        AwsDurableInputDecoderRenderer renderer = new AwsDurableInputDecoderRenderer();
        PipelineTemplateTypeModel typeModel = new PipelineTemplateTypeModel(Map.of(
            "Order", new PipelineTemplateTypeDefinition.RecordType("Order", List.of())));
        javax.annotation.processing.ProcessingEnvironment processing = org.mockito.Mockito.mock(
            javax.annotation.processing.ProcessingEnvironment.class);
        javax.lang.model.util.Elements elements = org.mockito.Mockito.mock(javax.lang.model.util.Elements.class);
        javax.lang.model.element.TypeElement order = org.mockito.Mockito.mock(
            javax.lang.model.element.TypeElement.class);
        org.mockito.Mockito.when(processing.getOptions()).thenReturn(Map.of());
        org.mockito.Mockito.when(processing.getElementUtils()).thenReturn(elements);
        org.mockito.Mockito.when(elements.getTypeElement("com.example.common.domain.Order")).thenReturn(order);
        org.mockito.Mockito.when(order.getKind()).thenReturn(javax.lang.model.element.ElementKind.RECORD);
        org.mockito.Mockito.when(order.getRecordComponents()).thenReturn(List.of());
        GenerationContext context = Jsr269GenerationContext.create(
            processing, output, DeploymentRole.PIPELINE_SERVER, Set.of(), null, null,
            PipelineTransport.REST, "com.example", null, true, Optional.of(typeModel));

        renderer.render(binding(false), context);

        String source = Files.readString(output.resolve(
            "com/example/orchestrator/service/AwsDurablePipelineInputDecoder.java"));
        assertTrue(source.contains("import com.example.dto.OrderDto;"));
        assertTrue(source.contains("mapper.readValue(inputJson, OrderDto.class)"));
        assertTrue(Files.exists(output.resolve("com/example/dto/OrderDto.java")));
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
