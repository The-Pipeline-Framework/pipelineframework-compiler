package org.pipelineframework.processor.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;

class HttpPayloadBoundaryRendererTest {
    @TempDir Path output;

    @Test
    void emitsDeterministicRoutesWithUploadAndAuthorisedDownload() throws Exception {
        Path template = output.resolve("pipeline.yaml");
        Files.writeString(template, """
            version: 3
            appName: Payload boundaries
            basePackage: org.example
            transport: REST
            platform: COMPUTE
            contract: { input: InvoiceInput, output: ReceiptOutput }
            types:
              InvoiceInput:
                fields:
                  - [payload_ref, payload_ref]
              ReceiptOutput:
                fields:
                  - [payload_ref, payload_ref]
            sources:
              receipts:
                kind: object
                provider: filesystem
                binding: files
            publish:
              invoices:
                kind: object
                provider: filesystem
                binding: files
            httpPayloads:
              receipt:
                direction: download
                object: receipts
                canonicalType: ReceiptOutput
                referenceField: payload_ref
                contentTypes: [application/pdf]
                authorizationScope: receipt.read
              invoice:
                direction: upload
                object: invoices
                canonicalType: InvoiceInput
                referenceField: payload_ref
                contentTypes: [application/pdf]
                maxBytes: 1024
                authorizationScope: invoice.write
            steps: []
            """);
        var config = new PipelineTemplateConfigLoader().load(template);
        var classes = new HttpPayloadBoundaryRenderer().render(config, output);
        assertEquals(List.of("org.example.pipeline.GeneratedPayloadBoundary0",
            "org.example.pipeline.GeneratedPayloadBoundary1"), classes);
        try (var sources = Files.walk(output)) {
            assertEquals(2, sources.filter(path -> path.toString().endsWith(".java")).count());
        }
        String uploadSource = Files.readString(output.resolve("org/example/pipeline/GeneratedPayloadBoundary0.java"));
        String downloadSource = Files.readString(output.resolve("org/example/pipeline/GeneratedPayloadBoundary1.java"));
        assertTrue(uploadSource.contains("@Authenticated"));
        assertTrue(uploadSource.contains("/tpf/payloads/invoice/upload"));
        assertTrue(uploadSource.contains("transfer().upload"));
        assertTrue(uploadSource.contains("application/pdf"));
        assertTrue(downloadSource.contains("/tpf/payloads/receipt/download"));
        assertTrue(downloadSource.contains("engine.openDownload"));
        assertTrue(downloadSource.contains("try (lease)"));

        // A route-name assertion alone does not prove repeatable generated artifacts.
        Path repeated = output.resolve("repeated");
        var repeatedClasses = new HttpPayloadBoundaryRenderer().render(
            new PipelineTemplateConfigLoader().load(template), repeated);
        assertEquals(classes, repeatedClasses);
        assertEquals(uploadSource,
            Files.readString(repeated.resolve("org/example/pipeline/GeneratedPayloadBoundary0.java")));
        assertEquals(downloadSource,
            Files.readString(repeated.resolve("org/example/pipeline/GeneratedPayloadBoundary1.java")));
    }
}
