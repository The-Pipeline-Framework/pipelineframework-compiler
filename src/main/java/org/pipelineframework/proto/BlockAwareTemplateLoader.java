package org.pipelineframework.proto;

import java.io.IOException;
import java.nio.file.Path;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.processor.block.BlockDefinitionImporter;
import org.pipelineframework.processor.block.ImportedPipelineSources;

/** Loads the same linked Block declarations used by the processor before target generation. */
final class BlockAwareTemplateLoader {
    PipelineTemplateConfig load(Path applicationConfig) {
        try (ImportedPipelineSources imported = new BlockDefinitionImporter(
                BlockAwareTemplateLoader.class.getClassLoader()).importForGeneration(applicationConfig)) {
            return new PipelineTemplateConfigLoader().load(imported.configPath());
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to close imported Block configuration", failure);
        }
    }
}
