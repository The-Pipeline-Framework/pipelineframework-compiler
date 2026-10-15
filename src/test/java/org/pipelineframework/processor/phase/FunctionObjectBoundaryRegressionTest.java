package org.pipelineframework.processor.phase;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.util.Elements;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.PlatformMode;
import org.pipelineframework.config.template.PipelinePlatform;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.processor.Jsr269SourceInventoryTestSupport;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.PipelineCompilerDiagnostics;

class FunctionObjectBoundaryRegressionTest {
    @TempDir Path directory;

    @Test
    void processorFunctionOverrideSelectsDelegatedRoutes() throws Exception {
        var context = discover(payloadYaml("COMPUTE", "s3"), "FUNCTION");
        assertEquals(PlatformMode.FUNCTION, context.getPlatformMode());
        assertEquals(PipelinePlatform.FUNCTION,
            ((PipelineTemplateConfig) context.getPipelineTemplateConfig()).platform());
        String source = generate(context);
        for (String route : java.util.List.of("initiate", "part", "complete", "abort")) {
            assertTrue(source.contains("@Path(\"" + route + "\")"), route);
        }
        assertFalse(source.contains("OwnedPayloadMultipart"));
    }

    @Test
    void processorComputeOverrideSelectsStreamingRoute() throws Exception {
        var context = discover(payloadYaml("FUNCTION", "s3"), "COMPUTE");
        assertEquals(PlatformMode.COMPUTE, context.getPlatformMode());
        assertEquals(PipelinePlatform.COMPUTE,
            ((PipelineTemplateConfig) context.getPipelineTemplateConfig()).platform());
        String source = generate(context);
        assertTrue(source.contains("OwnedPayloadMultipart"));
        assertFalse(source.contains("initiateDelegatedUpload"));
    }

    @Test
    void functionOverrideStillRejectsFilesystemPayloads() {
        assertThrows(RuntimeException.class,
            () -> discover(payloadYaml("COMPUTE", "filesystem"), "FUNCTION"));
    }

    @Test
    void functionOverrideStillRejectsActualCheckpointSubscriptions() throws Exception {
        String yaml = """
            version: 3
            appName: Checkpoint
            basePackage: org.example
            transport: REST
            platform: COMPUTE
            types:
              Input:
                fields: [[id, string]]
            input:
              subscription:
                publication: ready
            steps:
              - name: Echo
                cardinality: ONE_TO_ONE
                input: Input
                output: Input
            """;
        var failure = assertThrows(RuntimeException.class, () -> discover(yaml, "FUNCTION"));
        assertTrue(failure.getMessage().contains(
            "Checkpoint publication/subscription is not supported on FUNCTION pipelines"), failure::getMessage);
    }

    @Test
    void functionS3ObjectIoIsNotCheckpointHandoff() throws Exception {
        Path yaml = directory.resolve("objects.yaml");
        Files.writeString(yaml, """
            version: 3
            appName: Objects
            basePackage: org.example
            transport: REST
            platform: FUNCTION
            types:
              Input:
                fields: [[id, string]]
            sources:
              files: {kind: object, provider: s3, binding: objects}
            publish:
              results: {kind: object, provider: s3, binding: objects}
            input:
              from: files
              emits: {type: org.example.Input, typeName: Input, mapper: org.example.InputMapper}
            output:
              to: results
              consumes: {type: org.example.Input, typeName: Input, mapper: org.example.OutputMapper}
            steps:
              - name: Echo
                cardinality: ONE_TO_ONE
                input: Input
                output: Input
            """);
        var template = new PipelineTemplateConfigLoader().load(yaml);
        // Model-only platform guard; JSR-269 mapper resolution has separate coverage.
        assertDoesNotThrow(() -> new CheckpointBoundaryValidator().validate(template, directory,
            null, mock(PipelineCompilerDiagnostics.class)));
    }

    private PipelineCompilationContext discover(String yaml, String platform) throws Exception {
        Path config = directory.resolve("pipeline.yaml");
        Files.writeString(config, yaml);
        Files.writeString(directory.resolve("pom.xml"), "<project/>");
        Files.createDirectories(directory.resolve("src/main/resources"));
        Files.writeString(directory.resolve("src/main/resources/application.properties"),
            "pipeline.orchestrator.mode=QUEUE_ASYNC\n");
        var environment = mock(ProcessingEnvironment.class);
        when(environment.getOptions()).thenReturn(Map.of("pipeline.config", config.toString(),
            "pipeline.platform", platform, "pipeline.transport", "REST",
            "pipeline.generatedSourcesDir", directory.resolve("target/generated-sources").toString(),
            "pipeline.moduleDir", directory.toString(),
            "pipeline.orchestrator.mode", "QUEUE_ASYNC"));
        when(environment.getMessager()).thenReturn(mock(Messager.class));
        when(environment.getElementUtils()).thenReturn(mock(Elements.class));
        when(environment.getSourceVersion()).thenReturn(SourceVersion.RELEASE_21);
        var filer = mock(Filer.class);
        var resource = mock(FileObject.class);
        when(resource.toUri()).thenReturn(directory.resolve("target/classes/probe").toUri());
        when(resource.openWriter()).thenAnswer(call -> new StringWriter());
        when(filer.createResource(any(StandardLocation.class), anyString(), anyString())).thenReturn(resource);
        when(filer.createResource(any(StandardLocation.class), anyString(), anyString(),
            nullable(javax.lang.model.element.Element[].class))).thenReturn(resource);
        var source = mock(JavaFileObject.class);
        when(source.openWriter()).thenAnswer(call -> new StringWriter());
        when(filer.createSourceFile(anyString(), nullable(javax.lang.model.element.Element[].class)))
            .thenReturn(source);
        when(environment.getFiler()).thenReturn(filer);
        var context = new PipelineCompilationContext(environment,
            Jsr269SourceInventoryTestSupport.snapshot(mock(RoundEnvironment.class)));
        new PipelineDiscoveryPhase().execute(context);
        return context;
    }

    private String generate(PipelineCompilationContext context) throws Exception {
        new PipelineGenerationPhase().execute(context);
        return Files.readString(context.getGeneratedSourcesRoot().resolve(
            "pipeline-server/org/example/pipeline/GeneratedPayloadBoundary0.java"));
    }

    private String payloadYaml(String platform, String provider) {
        return """
            version: 3
            appName: Payload
            basePackage: org.example
            transport: REST
            platform: %s
            contract: {input: Input, output: Input}
            types:
              Input:
                fields: [['contentRef?', payload_ref]]
            publish:
              files: {kind: object, provider: %s, binding: objects}
            httpPayloads:
              upload:
                direction: upload
                object: files
                canonicalType: Input
                referenceField: contentRef
                contentTypes: [text/csv]
                maxBytes: 1048576
                authorizationScope: csv.upload
            steps: []
            """.formatted(platform, provider);
    }
}
