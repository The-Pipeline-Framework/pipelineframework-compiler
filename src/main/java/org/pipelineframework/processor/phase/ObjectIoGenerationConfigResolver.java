package org.pipelineframework.processor.phase;

import java.util.Optional;

import org.pipelineframework.config.pipeline.PipelineYamlConfig;
import org.pipelineframework.processor.PipelineCompilationContext;

/** Finds V3 Object I/O boundaries and reports unsupported legacy boundaries. */
final class ObjectIoGenerationConfigResolver {
    Optional<String> objectPublishGenerationConfig(PipelineCompilationContext ctx) {
        var pipelineTemplate = ctx.getPipelineTemplateConfig();
        if (pipelineTemplate != null && pipelineTemplate.version() == 3) {
            return pipelineTemplate.output() != null && pipelineTemplate.output().object() != null
                ? Optional.of(pipelineTemplate.basePackage()) : Optional.empty();
        }
        boolean legacyObject = pipelineTemplate != null
            && pipelineTemplate.output() != null && pipelineTemplate.output().object() != null;
        PipelineYamlConfig effective = ctx.getEffectivePipelineConfig();
        if (legacyObject || (effective != null && effective.output() != null && effective.output().object() != null)) {
            ctx.getCompilerDiagnostics().error("Object Publish requires a v3 pipeline template");
        }
        return Optional.empty();
    }

    Optional<String> objectIngestGenerationConfig(PipelineCompilationContext ctx) {
        var pipelineTemplate = ctx.getPipelineTemplateConfig();
        if (pipelineTemplate != null && pipelineTemplate.version() == 3) {
            return pipelineTemplate.input() != null && pipelineTemplate.input().object() != null
                ? Optional.of(pipelineTemplate.basePackage()) : Optional.empty();
        }
        boolean legacyObject = pipelineTemplate != null
            && pipelineTemplate.input() != null && pipelineTemplate.input().object() != null;
        PipelineYamlConfig effective = ctx.getEffectivePipelineConfig();
        if (legacyObject || (effective != null && effective.input() != null && effective.input().object() != null)) {
            ctx.getCompilerDiagnostics().error("Object Ingest requires a v3 pipeline template");
        }
        return Optional.empty();
    }
}
