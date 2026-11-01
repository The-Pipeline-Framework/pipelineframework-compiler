package org.pipelineframework.processor.routing;

import java.util.LinkedHashMap;
import java.util.List;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateDialect;
import org.pipelineframework.config.template.PipelineTemplateStep;
import org.pipelineframework.config.template.PipelineTemplateTypeModel;

/** Validates canonical authored consumers before framework lifecycle and observer expansion. */
public final class AuthoredConsumerValidator {
    public void validate(PipelineTemplateConfig config) {
        if (config.dialect() != PipelineTemplateDialect.V3) {
            return;
        }
        validateDefinition("$root", config.steps(), config.typeModel());
        config.pipelines().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .forEach(entry -> validateDefinition(entry.getKey(), entry.getValue().steps(), config.typeModel()));
    }

    private void validateDefinition(String id, List<PipelineTemplateStep> steps, PipelineTemplateTypeModel types) {
        var consumers = new LinkedHashMap<String, String>();
        for (var step : steps) {
            var accepted = step.accepts().isEmpty() ? List.of(step.inputTypeName()) : step.accepts();
            var concrete = new java.util.TreeSet<String>();
            for (String type : accepted) {
                concrete.addAll(types.concreteTypeNames(type));
            }
            for (String type : concrete) {
                String previous = consumers.putIfAbsent(type, step.name());
                if (previous != null) {
                    throw new IllegalArgumentException("Pipeline definition '" + id + "' assigns concrete input type '"
                        + type + "' to two authored steps: '" + previous + "' and '" + step.name() + "'.");
                }
            }
        }
    }

}
