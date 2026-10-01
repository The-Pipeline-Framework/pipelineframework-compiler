package org.pipelineframework.processor.renderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.LocalBinding;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.ServiceApiKind;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalClientStepRendererTest {

    @TempDir
    Path tempDir;

    @Test
    void forwardsPageOpenThroughLocalSourceClient() throws Exception {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName("ProcessCsvPaymentsInputService")
            .generatedName("ProcessCsvPaymentsInput")
            .servicePackage("org.pipelineframework.csv.service")
            .serviceClassName(ClassName.get("org.pipelineframework.csv.service", "ProcessCsvPaymentsInputService"))
            .streamingShape(StreamingShape.UNARY_STREAMING)
            .executionMode(ExecutionMode.DEFAULT)
            .serviceApiKind(ServiceApiKind.BLOCKING_ITERATOR)
            .inputMapping(new TypeMapping(ClassName.get("org.pipelineframework.csv.domain", "CsvPaymentsInputFile"), null, false))
            .outputMapping(new TypeMapping(ClassName.get("org.pipelineframework.csv.domain", "PaymentRecord"), null, false))
            .enabledTargets(Set.of())
            .build();

        new LocalClientStepRenderer().render(new LocalBinding(model),
            Jsr269GenerationContext.create(null, tempDir, DeploymentRole.ORCHESTRATOR_CLIENT, Set.of(), null, null));

        String source = Files.readString(tempDir.resolve(
            "org/pipelineframework/csv/service/pipeline/ProcessCsvPaymentsInputLocalClientStep.java"));
        assertTrue(source.contains("PagedSourceOperation<CsvPaymentsInputFile, PaymentRecord>"));
        assertTrue(source.contains("this.service).openPage(request)"));
        assertTrue(source.contains("this.service.process(input)"));
    }
}
