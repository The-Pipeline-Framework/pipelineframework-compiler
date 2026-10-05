package org.pipelineframework.processor.phase;

import java.util.Optional;

import org.pipelineframework.config.pipeline.PipelineYamlConfig;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.processor.PipelineCompilationContext;

/** Finds V3 Object I/O boundaries and reports unsupported legacy boundaries. */
final class ObjectIoGenerationConfigResolver {
    Optional<String> objectPublishGenerationConfig(PipelineCompilationContext ctx) {
        if (ctx.getPipelineTemplateConfig() instanceof PipelineTemplateConfig template && template.version() == 3) {
            return template.output() != null && template.output().object() != null
                ? Optional.of(template.basePackage()) : Optional.empty();
        }
        boolean legacyObject = ctx.getPipelineTemplateConfig() instanceof PipelineTemplateConfig template
            && template.output() != null && template.output().object() != null;
        PipelineYamlConfig effective = ctx.getEffectivePipelineConfig();
        if (legacyObject || (effective != null && effective.output() != null && effective.output().object() != null)) {
            ctx.getCompilerDiagnostics().error("Object Publish requires a v3 pipeline template");
        }
        return Optional.empty();
    }

    Optional<String> objectIngestGenerationConfig(PipelineCompilationContext ctx) {
        if (ctx.getPipelineTemplateConfig() instanceof PipelineTemplateConfig template && template.version() == 3) {
            return template.input() != null && template.input().object() != null
                ? Optional.of(template.basePackage()) : Optional.empty();
        }
        boolean legacyObject = ctx.getPipelineTemplateConfig() instanceof PipelineTemplateConfig template
            && template.input() != null && template.input().object() != null;
        PipelineYamlConfig effective = ctx.getEffectivePipelineConfig();
        if (legacyObject || (effective != null && effective.input() != null && effective.input().object() != null)) {
            ctx.getCompilerDiagnostics().error("Object Ingest requires a v3 pipeline template");
        }
        return Optional.empty();
    }
}
