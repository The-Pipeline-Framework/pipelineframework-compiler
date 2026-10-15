package org.pipelineframework.processor.renderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.processor.ir.*;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.config.template.PipelineTemplateTypeModel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestResourceRendererTest {

    @TempDir
    Path tempDir;

    @Test
    void functionBuffersFiniteTypedResultsWhileComputeRetainsSse() throws IOException {
        var model = baseModelBuilder("ExpandService", StreamingShape.UNARY_STREAMING)
            .inputMapping(new TypeMapping(ClassName.get("example.common.domain", "Input"),
                ClassName.get("example", "InputMapper"), true))
            .outputMapping(new TypeMapping(ClassName.get("example.common.domain", "Output"),
                ClassName.get("example", "OutputMapper"), true)).build();
        for (var platform : org.pipelineframework.config.PlatformMode.values()) {
            var context = createContext().withPlatformMode(platform);
            new RestResourceRenderer().render(new RestBinding(model, "/expand"), context);
            var source = Files.readString(tempDir.resolve("org/pipelineframework/csv/service/pipeline/ExpandResource.java"));
            if (platform == org.pipelineframework.config.PlatformMode.FUNCTION) {
                assertTrue(source.contains("Uni<List<OutputDto>> process(InputDto inputDto)"));
                assertTrue(source.contains(".collect().asList()"));
                assertTrue(source.contains("@Produces(\"application/json\")"));
                org.junit.jupiter.api.Assertions.assertFalse(source.contains("@RestStreamElementType"));
            } else {
                assertTrue(source.contains("Multi<OutputDto> process(InputDto inputDto)"));
                assertTrue(source.contains("@RestStreamElementType(\"application/json\")"));
                org.junit.jupiter.api.Assertions.assertFalse(source.contains(".collect().asList()"));
            }
        }
    }

    @Test
    void v3CacheResourceKeepsTransportDtoAcrossCacheBoundary() throws IOException {
        ClassName document = ClassName.get("org.example.canonical", "Document");
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName("ObserveCacheDocumentSideEffectService")
            .servicePackage("org.example.service")
            .serviceClassName(ClassName.get("org.pipelineframework.plugin.cache", "CacheService"))
            .streamingShape(StreamingShape.UNARY_UNARY)
            .executionMode(ExecutionMode.DEFAULT)
            .sideEffect(true)
            .inputMapping(TypeMapping.canonical(document, "Document"))
            .outputMapping(TypeMapping.canonical(document, "Document"))
            .enabledTargets(Set.of(GenerationTarget.REST_RESOURCE))
            .build();
        PipelineTemplateTypeModel types = new PipelineTemplateTypeModel(Map.of(
            "Document", new PipelineTemplateTypeDefinition.RecordType("Document", List.of())));
        ProcessingEnvironment environment = mock(ProcessingEnvironment.class);
        Elements elements = mock(Elements.class);
        TypeElement record = mock(TypeElement.class);
        when(environment.getElementUtils()).thenReturn(elements);
        when(elements.getTypeElement("org.example.canonical.Document")).thenReturn(record);
        when(record.getKind()).thenReturn(ElementKind.RECORD);
        when(record.getRecordComponents()).thenReturn(List.of());
        GenerationContext context = Jsr269GenerationContext.create(environment, tempDir,
            DeploymentRole.REST_SERVER, Set.of(), null, null, PipelineTransport.REST, "org.example", null,
            false, Optional.of(types));

        new RestResourceRenderer().render(new RestBinding(model, "/document/cache"), context);

        String source = Files.readString(tempDir.resolve(
            "org/example/service/pipeline/ObserveCacheDocumentSideEffectResource.java"));
        assertTrue(source.contains("RestReactiveServiceAdapter<DocumentDto, DocumentDto, DocumentDto, DocumentDto>"));
        assertTrue(source.contains("return domainService.process(inputDto);"));
    }

    @Test
    void pagedSourceResourceDelegatesToProviderAndEmitsPageFrames() throws IOException {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName("ReadPaymentsService")
            .servicePackage("org.pipelineframework.csv.service")
            .serviceClassName(ClassName.get("org.pipelineframework.csv.service", "ReadPaymentsService"))
            .streamingShape(StreamingShape.UNARY_STREAMING)
            .executionMode(ExecutionMode.DEFAULT)
            .pagedSource(true)
            .inputMapping(new TypeMapping(ClassName.get("org.pipelineframework.csv.domain", "Input"),
                ClassName.get("org.pipelineframework.csv.mapper", "InputMapper"), true))
            .outputMapping(new TypeMapping(ClassName.get("org.pipelineframework.csv.domain", "Record"),
                ClassName.get("org.pipelineframework.csv.mapper", "RecordMapper"), true))
            .enabledTargets(Set.of(GenerationTarget.REST_RESOURCE))
            .build();
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        GenerationContext context = Jsr269GenerationContext.create(env, tempDir,
            DeploymentRole.REST_SERVER, Set.of(), null, null);
        new RestResourceRenderer().render(new RestBinding(model, "/source"), context);
        String source = Files.readString(tempDir.resolve(
            "org/pipelineframework/csv/service/pipeline/ReadPaymentsResource.java"));
        assertTrue(source.contains("@Path(\"/page\")"));
        assertTrue(source.contains("pageBridge.validateRelease"));
        assertTrue(source.contains("source.openPage(pageRequest)"));
        assertTrue(source.contains("pageBridge.serve(opened, pageRequest"));
    }

    @Test
    void rendersUnaryResourceMatchingCsvPaymentExample() throws IOException {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName("ProcessPaymentStatusReactiveService")
            .servicePackage("org.pipelineframework.csv.service")
            .serviceClassName(ClassName.get(
                "org.pipelineframework.csv.service",
                "ProcessPaymentStatusReactiveService"))
            .streamingShape(StreamingShape.UNARY_UNARY)
            .executionMode(ExecutionMode.DEFAULT)
            .inputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.csv.common.domain", "PaymentStatus"),
                ClassName.get("org.pipelineframework.csv.common.mapper", "PaymentStatusMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.csv.common.domain", "PaymentOutput"),
                ClassName.get("org.pipelineframework.csv.common.mapper", "PaymentOutputMapper"),
                true))
            .enabledTargets(java.util.Set.of(GenerationTarget.REST_RESOURCE))
            .build();

        RestBinding binding = new RestBinding(
            model,
            "/ProcessPaymentStatusReactiveService/remoteProcess");

        ProcessingEnvironment processingEnv = mock(ProcessingEnvironment.class);
        GenerationContext context = Jsr269GenerationContext.create(processingEnv, tempDir, DeploymentRole.REST_SERVER,
            java.util.Set.of(), null, null);

        RestResourceRenderer renderer = new RestResourceRenderer();
        renderer.render(binding, context);

        Path generatedSource = tempDir.resolve("org/pipelineframework/csv/service/pipeline/ProcessPaymentStatusResource.java");
        String source = Files.readString(generatedSource);

        assertTrue(source.contains("package org.pipelineframework.csv.service.pipeline;"));
        assertTrue(source.contains("@GeneratedRole("));
        assertTrue(source.contains("REST_SERVER"));
        assertTrue(source.contains("@Path(\"/ProcessPaymentStatusReactiveService/remoteProcess\")"));
        assertTrue(source.contains("class ProcessPaymentStatusResource"));
        assertTrue(source.contains("ProcessPaymentStatusReactiveService domainService;"));
        assertTrue(source.contains("Mapper<PaymentStatus, PaymentStatusDto> inboundMapper;"));
        assertTrue(source.contains("Mapper<PaymentOutput, PaymentOutputDto> outboundMapper;"));
        assertTrue(source.contains("@POST"));
        assertTrue(source.contains("@Path(\"/\")"));
        assertTrue(source.contains("public Uni<PaymentOutputDto> process(PaymentStatusDto inputDto)"));
        assertTrue(source.contains("PaymentStatus inputDomain = inboundMapper.fromExternal(inputDto);"));
        assertTrue(source.contains("return domainService.process(inputDomain).map(output -> outboundMapper.toExternal(output));"));
    }

    @Test
    void rendersStreamingCacheSideEffectWithoutMappers() {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName("ObserveCacheSomethingSideEffectService")
            .servicePackage("org.pipelineframework.cache.service")
            .serviceClassName(ClassName.get(
                "org.pipelineframework.plugin.cache",
                "CacheService"))
            .streamingShape(StreamingShape.UNARY_STREAMING)
            .executionMode(ExecutionMode.DEFAULT)
            .sideEffect(true)
            .inputMapping(new TypeMapping(
                ClassName.get("com.example.domain", "InputType"),
                null,
                false))
            .outputMapping(new TypeMapping(
                ClassName.get("com.example.domain", "OutputType"),
                null,
                false))
            .enabledTargets(java.util.Set.of(GenerationTarget.REST_RESOURCE))
            .build();

        RestBinding binding = new RestBinding(
            model,
            "/ObserveCacheSomethingSideEffectService/remoteProcess");

        ProcessingEnvironment processingEnv = mock(ProcessingEnvironment.class);
        GenerationContext context = Jsr269GenerationContext.create(processingEnv, tempDir, DeploymentRole.REST_SERVER,
            java.util.Set.of(), null, null);

        RestResourceRenderer renderer = new RestResourceRenderer();
        assertDoesNotThrow(() -> renderer.render(binding, context));
    }

    @Test
    void derivesResourcefulPathFromOutputTypeForUnaryUnary() throws IOException {
        PipelineStepModel model = baseModelBuilder("ProcessInvoiceApprovalService", StreamingShape.UNARY_UNARY)
            .inputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.example.common.domain", "InvoiceApproval"),
                ClassName.get("org.pipelineframework.example.common.mapper", "InvoiceApprovalMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.example.common.domain", "InvoiceSettlement"),
                ClassName.get("org.pipelineframework.example.common.mapper", "InvoiceSettlementMapper"),
                true))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/ProcessInvoiceApprovalResource.java");
        assertTrue(source.contains("@Path(\"/api/v1/invoice-settlement\")"));
        assertTrue(source.contains("@Path(\"/\")"));
    }

    @Test
    void derivesResourcefulPathFromInputTypeForUnaryStreaming() throws IOException {
        PipelineStepModel model = baseModelBuilder("ProcessInvoiceApprovalService", StreamingShape.UNARY_STREAMING)
            .inputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.example.common.domain", "InvoiceApproval"),
                ClassName.get("org.pipelineframework.example.common.mapper", "InvoiceApprovalMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.example.common.domain", "InvoiceSettlement"),
                ClassName.get("org.pipelineframework.example.common.mapper", "InvoiceSettlementMapper"),
                true))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/ProcessInvoiceApprovalResource.java");
        assertTrue(
            source.contains("@Path(\"/api/v1/invoice-approval\")"),
            "expected class-level @Path for invoice-approval not found");
        assertTrue(
            source.contains("@Path(\"/\")"),
            "expected method-level @Path for resourceful operation not found");
    }

    @Test
    void derivesPluginSegmentForSideEffectPaths() throws IOException {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName("ObservePersistenceInvoiceApprovalSideEffectService")
            .generatedName("PersistenceInvoiceApprovalSideEffect")
            .servicePackage("org.pipelineframework.csv.service")
            .serviceClassName(ClassName.get(
                "org.pipelineframework.plugin.persistence",
                "PersistenceService"))
            .streamingShape(StreamingShape.UNARY_UNARY)
            .executionMode(ExecutionMode.DEFAULT)
            .sideEffect(true)
            .inputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.example.common.domain", "InvoiceApproval"),
                ClassName.get("org.pipelineframework.example.common.mapper", "InvoiceApprovalMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.example.common.domain", "InvoiceApproval"),
                ClassName.get("org.pipelineframework.example.common.mapper", "InvoiceApprovalMapper"),
                true))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/PersistenceInvoiceApprovalSideEffectResource.java");
        assertTrue(source.contains("@Path(\"/api/v1/invoice-approval/persistence\")"));
    }

    private PipelineStepModel.Builder baseModelBuilder(String serviceName, StreamingShape shape) {
        return new PipelineStepModel.Builder()
            .serviceName(serviceName)
            .servicePackage("org.pipelineframework.csv.service")
            .serviceClassName(ClassName.get("org.pipelineframework.csv.service", serviceName))
            .streamingShape(shape)
            .executionMode(ExecutionMode.DEFAULT)
            .enabledTargets(Set.of(GenerationTarget.REST_RESOURCE));
    }

    private GenerationContext createContext() {
        ProcessingEnvironment processingEnv = mock(ProcessingEnvironment.class);
        return Jsr269GenerationContext.create(processingEnv, tempDir, DeploymentRole.REST_SERVER, Set.of(), null, null);
    }

    private String renderAndReadSource(RestBinding binding, String resourceFileName) throws IOException {
        new RestResourceRenderer().render(binding, createContext());
        Path generatedSource = tempDir.resolve(resourceFileName);
        return Files.readString(generatedSource);
    }

    @Test
    void rendersStreamingUnaryResourceWithListParameter() throws IOException {
        PipelineStepModel model = baseModelBuilder("AggregatePaymentsService", StreamingShape.STREAMING_UNARY)
            .inputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Payment"),
                ClassName.get("org.example.mapper", "PaymentMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Summary"),
                ClassName.get("org.example.mapper", "SummaryMapper"),
                true))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/AggregatePaymentsResource.java");

        assertTrue(source.contains("public Uni<SummaryDto> process(List<PaymentDto> inputDtos)"));
        assertTrue(source.contains("Multi.createFrom().iterable(inputDtos)"));
        assertTrue(source.contains("RestReactiveStreamingClientServiceAdapter"));
    }

    @Test
    void rendersStreamingStreamingResourceWithNdjson() throws IOException {
        PipelineStepModel model = baseModelBuilder("TransformPaymentsService", StreamingShape.STREAMING_STREAMING)
            .inputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "RawPayment"),
                ClassName.get("org.example.mapper", "RawPaymentMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "ProcessedPayment"),
                ClassName.get("org.example.mapper", "ProcessedPaymentMapper"),
                true))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/TransformPaymentsResource.java");

        assertTrue(source.contains("public Multi<ProcessedPaymentDto> process(Multi<RawPaymentDto> inputDtos)"));
        assertTrue(source.contains("@Consumes(\"application/x-ndjson\")"));
        assertTrue(source.contains("@Produces(\"application/x-ndjson\")"));
        assertTrue(source.contains("RestReactiveBidirectionalStreamingServiceAdapter"));
    }

    @Test
    void rendersResourceWithVirtualThreads() throws IOException {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName("ProcessPaymentService")
            .servicePackage("org.example.service")
            .serviceClassName(ClassName.get("org.example.service", "ProcessPaymentService"))
            .streamingShape(StreamingShape.UNARY_UNARY)
            .executionMode(ExecutionMode.VIRTUAL_THREADS)
            .inputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Payment"),
                ClassName.get("org.example.mapper", "PaymentMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Receipt"),
                ClassName.get("org.example.mapper", "ReceiptMapper"),
                true))
            .enabledTargets(Set.of(GenerationTarget.REST_RESOURCE))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/example/service/pipeline/ProcessPaymentResource.java");

        assertTrue(source.contains("@RunOnVirtualThread"));
    }

    @Test
    void verifyTargetReturnsRestResource() {
        RestResourceRenderer renderer = new RestResourceRenderer();
        assertEquals(GenerationTarget.REST_RESOURCE, renderer.target());
    }

    @Test
    void rendersResourceWithCustomPathOverride() throws IOException {
        PipelineStepModel model = baseModelBuilder("ProcessPaymentService", StreamingShape.UNARY_UNARY)
            .inputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Payment"),
                ClassName.get("org.example.mapper", "PaymentMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Receipt"),
                ClassName.get("org.example.mapper", "ReceiptMapper"),
                true))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, "/custom/payment/path"),
            "org/pipelineframework/csv/service/pipeline/ProcessPaymentResource.java");

        assertTrue(source.contains("@Path(\"/custom/payment/path\")"));
    }

    @Test
    void rendersGetServiceMethodForUnaryUnary() throws IOException {
        PipelineStepModel model = baseModelBuilder("ProcessPaymentService", StreamingShape.UNARY_UNARY)
            .inputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Payment"),
                ClassName.get("org.example.mapper", "PaymentMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Receipt"),
                ClassName.get("org.example.mapper", "ReceiptMapper"),
                true))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/ProcessPaymentResource.java");

        assertTrue(source.contains("protected ReactiveService<Payment, Receipt> getService()"));
        assertTrue(source.contains("return domainService"));
    }

    @Test
    void rendersFromDtoMethodWithMapping() throws IOException {
        PipelineStepModel model = baseModelBuilder("ProcessPaymentService", StreamingShape.UNARY_UNARY)
            .inputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Payment"),
                ClassName.get("org.example.mapper", "PaymentMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Receipt"),
                ClassName.get("org.example.mapper", "ReceiptMapper"),
                true))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/ProcessPaymentResource.java");

        assertTrue(source.contains("protected Payment fromDto(PaymentDto dto)"));
        assertTrue(source.contains("return inboundMapper.fromExternal(dto)"));
    }

    @Test
    void rendersToDtoMethodWithMapping() throws IOException {
        PipelineStepModel model = baseModelBuilder("ProcessPaymentService", StreamingShape.UNARY_UNARY)
            .inputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Payment"),
                ClassName.get("org.example.mapper", "PaymentMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Receipt"),
                ClassName.get("org.example.mapper", "ReceiptMapper"),
                true))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/ProcessPaymentResource.java");

        assertTrue(source.contains("protected ReceiptDto toDto(Receipt domain)"));
        assertTrue(source.contains("return outboundMapper.toExternal(domain)"));
    }

    @Test
    void rendersPostAnnotationForProcessMethod() throws IOException {
        PipelineStepModel model = baseModelBuilder("ProcessPaymentService", StreamingShape.UNARY_UNARY)
            .inputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Payment"),
                ClassName.get("org.example.mapper", "PaymentMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Receipt"),
                ClassName.get("org.example.mapper", "ReceiptMapper"),
                true))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/ProcessPaymentResource.java");

        assertTrue(source.contains("@POST"));
    }

    @Test
    void rendersRestStreamElementTypeForUnaryStreaming() throws IOException {
        PipelineStepModel model = baseModelBuilder("StreamPaymentsService", StreamingShape.UNARY_STREAMING)
            .inputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Query"),
                ClassName.get("org.example.mapper", "QueryMapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.example.domain", "Payment"),
                ClassName.get("org.example.mapper", "PaymentMapper"),
                true))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/StreamPaymentsResource.java");

        assertTrue(source.contains("@RestStreamElementType(\"application/json\")"));
    }

    @Test
    void blockingServicesInjectGeneratedReactiveBridge() throws IOException {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName("ProcessCsvPaymentsInputService")
            .servicePackage("org.pipelineframework.csv.service")
            .serviceClassName(ClassName.get("org.pipelineframework.csv.service", "ProcessCsvPaymentsInputService"))
            .streamingShape(StreamingShape.UNARY_STREAMING)
            .executionMode(ExecutionMode.DEFAULT)
            .serviceApiKind(ServiceApiKind.BLOCKING)
            .inputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.csv.common.domain", "CsvPaymentsInputFile"),
                null,
                false))
            .outputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.csv.common.domain", "PaymentRecord"),
                null,
                false))
            .enabledTargets(Set.of(GenerationTarget.REST_RESOURCE))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/ProcessCsvPaymentsInputResource.java");

        assertTrue(source.contains("ProcessCsvPaymentsInputServiceBlockingReactiveBridge domainService;"));
    }

    @Test
    void blockingIteratorServicesInjectGeneratedReactiveBridge() throws IOException {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName("ProcessCsvPaymentsInputService")
            .servicePackage("org.pipelineframework.csv.service")
            .serviceClassName(ClassName.get("org.pipelineframework.csv.service", "ProcessCsvPaymentsInputService"))
            .streamingShape(StreamingShape.UNARY_STREAMING)
            .executionMode(ExecutionMode.DEFAULT)
            .serviceApiKind(ServiceApiKind.BLOCKING_ITERATOR)
            .inputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.csv.common.domain", "CsvPaymentsInputFile"),
                null,
                false))
            .outputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.csv.common.domain", "PaymentRecord"),
                null,
                false))
            .enabledTargets(Set.of(GenerationTarget.REST_RESOURCE))
            .build();

        String source = renderAndReadSource(
            new RestBinding(model, null),
            "org/pipelineframework/csv/service/pipeline/ProcessCsvPaymentsInputResource.java");

        assertTrue(source.contains("ProcessCsvPaymentsInputServiceBlockingReactiveBridge domainService;"));
    }
}
