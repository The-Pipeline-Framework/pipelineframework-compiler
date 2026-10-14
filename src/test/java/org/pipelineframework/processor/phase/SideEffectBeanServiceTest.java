package org.pipelineframework.processor.phase;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.squareup.javapoet.ClassName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.util.Elements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateDialect;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.config.template.PipelineTemplateTypeModel;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.GrpcBinding;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

class SideEffectBeanServiceTest {
    @TempDir
    Path output;

    @Test
    void v3RestCacheBeanObservesCanonicalRecord() throws Exception {
        PipelineCompilationContext context = mock(PipelineCompilationContext.class);
        ProcessingEnvironment environment = mock(ProcessingEnvironment.class);
        when(context.getProcessingEnv()).thenReturn(environment);
        when(environment.getElementUtils()).thenReturn(mock(Elements.class));
        when(context.getGeneratedSourcesRoot()).thenReturn(output);
        PipelineTemplateConfig template = mock(PipelineTemplateConfig.class);
        when(template.dialect()).thenReturn(PipelineTemplateDialect.V3);
        when(template.basePackage()).thenReturn("org.example");
        when(template.typeModel()).thenReturn(new PipelineTemplateTypeModel(Map.of(
            "Document", new PipelineTemplateTypeDefinition.RecordType("Document", List.of()))));
        when(context.getPipelineTemplateConfig()).thenReturn(template);
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
            .enabledTargets(Set.of())
            .build();

        new SideEffectBeanService(new GenerationPathResolver()).generateSideEffectBean(
            context, model, DeploymentRole.REST_SERVER, DeploymentRole.REST_SERVER, mock(GrpcBinding.class));

        String source = Files.readString(output.resolve(
            "rest-server/org/example/service/pipeline/ObserveCacheDocumentSideEffectService.java"));
        assertTrue(source.contains("extends CacheService<DocumentDto>"));
        assertTrue(source.contains("import org.example.dto.DocumentDto;"));
    }
}
