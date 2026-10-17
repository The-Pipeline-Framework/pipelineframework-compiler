package org.pipelineframework.processor.renderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.processing.ProcessingEnvironment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.processor.ir.*;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrchestratorRestResourceRendererTest {

    @TempDir
    Path tempDir;

    @Test
    void rendersUnaryRestResource() throws IOException {
        OrchestratorBinding binding = buildBinding(false, false);
        ProcessingEnvironment processingEnv = mock(ProcessingEnvironment.class);
        when(processingEnv.getFiler()).thenReturn(new TestFiler(tempDir));

        OrchestratorRestResourceRenderer renderer = new OrchestratorRestResourceRenderer();
        renderer.render(binding, Jsr269GenerationContext.create(processingEnv, tempDir, DeploymentRole.REST_SERVER,
            java.util.Set.of(), null, null));

        Path generatedSource = tempDir.resolve("com/example/orchestrator/service/PipelineRunResource.java");
        String source = Files.readString(generatedSource);

        assertTrue(source.contains("package com.example.orchestrator.service;"));
        assertTrue(source.contains("@Path(\"/pipeline\")"));
        assertTrue(source.contains("@ApplicationScoped"));
        assertTrue(source.contains("@Path(\"/run\")"));
        assertTrue(source.contains("public Uni<OutputTypeDto> run(InputTypeDto input)"));
        assertTrue(source.contains("executePipelineUnary"));
        assertTrue(source.contains("pipelineOutputBus"));
        assertTrue(source.contains("@Path(\"/run-async\")"));
        assertTrue(source.contains("executePipelineAsync"));
        assertTrue(source.contains("@Path(\"/executions/{executionId}\")"));
        assertTrue(source.contains("@Path(\"/executions/{executionId}/result\")"));
        assertTrue(source.contains("@Path(\"/interactions/complete\")"));
        assertTrue(source.contains("SecurityContext securityContext"));
        assertTrue(source.contains("request.resumeToken()"));
        assertTrue(source.contains("Principal principal = securityContext == null ? null : securityContext.getUserPrincipal()"));
        assertTrue(source.contains("actor = principal.getName()"));
        assertTrue(source.contains("@Path(\"/ingest\")"));
        assertTrue(source.contains("@Path(\"/subscribe\")"));
    }

    @Test
    void rendersStreamingRestResource() throws IOException {
        OrchestratorBinding binding = buildBinding(true, true);
        ProcessingEnvironment processingEnv = mock(ProcessingEnvironment.class);
        when(processingEnv.getFiler()).thenReturn(new TestFiler(tempDir));

        OrchestratorRestResourceRenderer renderer = new OrchestratorRestResourceRenderer();
        renderer.render(binding, Jsr269GenerationContext.create(processingEnv, tempDir, DeploymentRole.REST_SERVER,
            java.util.Set.of(), null, null));

        Path generatedSource = tempDir.resolve("com/example/orchestrator/service/PipelineRunResource.java");
        String source = Files.readString(generatedSource);

        assertTrue(source.contains("@Consumes(\"application/x-ndjson\")"));
        assertTrue(source.contains("@Produces(\"application/x-ndjson\")"));
        assertTrue(source.contains("@RestStreamElementType(\"application/json\")"));
        assertTrue(source.contains("public Multi<OutputTypeDto> run(Multi<InputTypeDto> input)"));
        assertTrue(source.contains("executePipelineStreaming"));
        assertTrue(source.contains("public Uni<RunAsyncAcceptedDto> runAsync(List<InputTypeDto> input"));
        assertTrue(source.contains("executePipelineAsync(Multi.createFrom().iterable(input)"));
        assertTrue(source.contains("@HeaderParam(\"x-pipeline-version\") String versionTag"));
        assertTrue(source.contains("@HeaderParam(\"x-pipeline-replay\") String replayMode"));
        assertTrue(source.contains("@HeaderParam(\"x-pipeline-cache-policy\") String cachePolicy"));
        assertTrue(source.contains("PipelineContext.fromHeaders(versionTag, replayMode, cachePolicy)"));
        assertTrue(source.contains("PipelineContext previousPipelineContext = PipelineContextHolder.get()"));
        assertTrue(source.contains("PipelineContextHolder.clear()"));
        assertTrue(source.contains("@Path(\"/executions/{executionId}\")"));
        assertTrue(source.contains("@Path(\"/executions/{executionId}/result\")"));
        assertTrue(source.contains("@Path(\"/ingest\")"));
        assertTrue(source.contains("@Path(\"/subscribe\")"));
    }

    @Test
    void canonicalValuesStayInsideEveryRestIngressAndResultBoundary() throws Exception {
        var processing = mock(ProcessingEnvironment.class);
        var elements = mock(javax.lang.model.util.Elements.class);
        var record = mock(javax.lang.model.element.TypeElement.class);
        when(processing.getElementUtils()).thenReturn(elements);
        when(processing.getOptions()).thenReturn(java.util.Map.of());
        when(elements.getTypeElement("example.domain.Document")).thenReturn(record);
        when(record.getKind()).thenReturn(javax.lang.model.element.ElementKind.RECORD);
        when(record.getRecordComponents()).thenReturn(java.util.List.of());
        var types = new org.pipelineframework.config.template.PipelineTemplateTypeModel(java.util.Map.of(
            "Document", new org.pipelineframework.config.template.PipelineTemplateTypeDefinition.RecordType(
                "Document", java.util.List.of())));
        var context = Jsr269GenerationContext.create(processing, tempDir, DeploymentRole.REST_SERVER,
            java.util.Set.of(), null, null, PipelineTransport.REST, "example", null, true,
            java.util.Optional.of(types));
        var mapping = TypeMapping.canonical(com.squareup.javapoet.ClassName.get("example.domain", "Document"), "Document");
        for (boolean inputStreaming : new boolean[]{false, true}) {
            for (boolean outputStreaming : new boolean[]{false, true}) {
                var model = new PipelineStepModel.Builder().serviceName("DocumentsService")
                    .servicePackage("example.service").serviceClassName(com.squareup.javapoet.ClassName.get("example.service", "DocumentsService"))
                    .streamingShape(streamingShape(inputStreaming, outputStreaming)).executionMode(ExecutionMode.DEFAULT)
                    .inputMapping(mapping).outputMapping(mapping).build();
                new OrchestratorRestResourceRenderer().render(new OrchestratorBinding(model, "example", "REST",
                    "Document", "Document", inputStreaming, outputStreaming, "DocumentsService",
                    model.streamingShape(), null, null, null), context);
                String source = Files.readString(tempDir.resolve("example/orchestrator/service/PipelineRunResource.java"));
                var mapperImport = java.util.regex.Pattern.compile(
                    "import example\\.transport\\.generated\\.([A-Za-z0-9_]+RestMapper);").matcher(source);
                assertTrue(mapperImport.find(), source);
                String mapper = mapperImport.group(1);
                String mappedItems = "input.onItem().transform(value->new" + mapper + "().fromExternal(value))";
                String mappedItem = "new" + mapper + "().fromExternal(input)";
                String wireResult = ".onItem().transform(value->new" + mapper + "().toExternal(value))";
                String run = generatedMethod(source, "run");
                assertTrue(run.contains("returnpipelineExecutionService.<Document>executePipeline"
                    + (outputStreaming ? "Streaming" : "Unary") + "("
                    + (inputStreaming ? mappedItems : "Uni.createFrom().item(" + mappedItem + ")")
                    + ").onItem().invoke(pipelineOutputBus::publish)" + wireResult + ";"), run);
                String ingest = generatedMethod(source, "ingest");
                assertTrue(ingest.contains("returnpipelineExecutionService.<Document>executePipelineStreaming("
                    + mappedItems + ").onItem().invoke(pipelineOutputBus::publish)" + wireResult + ";"), ingest);
                String async = generatedMethod(source, "runAsync");
                assertTrue(async.contains("executePipelineAsync("
                    + (inputStreaming ? "Multi.createFrom().iterable(input).onItem().transform(value->new"
                        + mapper + "().fromExternal(value))" : mappedItem)
                    + ",tenantId,idempotencyKey," + outputStreaming + ")"), async);
                String result = generatedMethod(source, "result");
                assertTrue(result.contains("returnpipelineExecutionService.<"
                    + (outputStreaming ? "List<Document>" : "Document")
                    + ">getExecutionResult(tenantId,executionId,Document.class," + outputStreaming + ")"
                    + (outputStreaming ? ".onItem().transform(items->items.stream().map(value->new"
                        + mapper + "().toExternal(value)).toList())" : wireResult) + ";"), result);
                assertTrue(generatedMethod(source, "subscribe").contains(
                    "returnpipelineOutputBus.stream(Document.class)" + wireResult + ";"));
                assertTrue(source.contains("public " + (outputStreaming ? "Multi" : "Uni") + "<DocumentDto> run("), source);
            }
        }
    }

    private static String generatedMethod(String source, String method) {
        int start = source.indexOf(" " + method + "(");
        assertTrue(start >= 0, method + " must exist");
        return source.substring(start, source.indexOf("\n  }", start)).replaceAll("\\s+", "");
    }

    private OrchestratorBinding buildBinding(boolean inputStreaming, boolean outputStreaming) {
        PipelineStepModel model = new PipelineStepModel(
            "OrchestratorService",
            "OrchestratorService",
            "com.example.orchestrator.service",
            com.squareup.javapoet.ClassName.get("com.example.orchestrator.service", "OrchestratorService"),
            null,
            null,
            streamingShape(inputStreaming, outputStreaming),
            java.util.Set.of(GenerationTarget.GRPC_SERVICE),
            ExecutionMode.DEFAULT,
            DeploymentRole.ORCHESTRATOR_CLIENT,
            false,
            null
        );

        return new OrchestratorBinding(
            model,
            "com.example",
            "REST",
            "InputType",
            "OutputType",
            inputStreaming,
            outputStreaming,
            "ProcessAlphaService",
            StreamingShape.UNARY_UNARY,
            null,
            null,
            null
        );
    }

    private StreamingShape streamingShape(boolean inputStreaming, boolean outputStreaming) {
        if (inputStreaming && outputStreaming) {
            return StreamingShape.STREAMING_STREAMING;
        }
        if (inputStreaming) {
            return StreamingShape.STREAMING_UNARY;
        }
        if (outputStreaming) {
            return StreamingShape.UNARY_STREAMING;
        }
        return StreamingShape.UNARY_UNARY;
    }
}
