package org.pipelineframework.processor.phase;

import java.util.Optional;

import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.processor.PipelineCompilationContext;

/** Finds V3 Object I/O boundaries in the discovered pipeline template. */
final class ObjectIoGenerationConfigResolver {
    Optional<String> objectPublishGenerationConfig(PipelineCompilationContext ctx) {
        if (ctx.getPipelineTemplateConfig() instanceof PipelineTemplateConfig template
                && template.version() == 3
                && template.output() != null
                && template.output().object() != null) {
            return Optional.of(template.basePackage());
        }
        return Optional.empty();
    }

    Optional<String> objectIngestGenerationConfig(PipelineCompilationContext ctx) {
        if (ctx.getPipelineTemplateConfig() instanceof PipelineTemplateConfig template
                && template.version() == 3
                && template.input() != null
                && template.input().object() != null) {
            return Optional.of(template.basePackage());
        }
        return Optional.empty();
    }
}
