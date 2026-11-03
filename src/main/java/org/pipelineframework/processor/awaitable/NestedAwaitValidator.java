package org.pipelineframework.processor.awaitable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateStep;
import org.pipelineframework.processor.parser.ParsedPipelineDefinitionCatalog;

/** Validates the complete invocation chain that a durable scalar suspension must restore. */
public final class NestedAwaitValidator {
    public void validate(PipelineTemplateConfig config, ParsedPipelineDefinitionCatalog parsed) {
        Map<String, List<PipelineTemplateStep>> definitions = new LinkedHashMap<>();
        definitions.put("$root", config.steps());
        config.pipelines().forEach((id, definition) -> definitions.put(id, definition.steps()));
        Set<String> suspending = new HashSet<>();
        if (parsed.rootSteps().stream().anyMatch(step -> step.deferredCompletion().isPresent())) {
            suspending.add("$root");
        }
        parsed.localDefinitions().forEach((id, steps) -> {
            if (steps.stream().anyMatch(step -> step.deferredCompletion().isPresent())) suspending.add(id);
        });
        for (var definition : definitions.entrySet()) {
            if (!"$root".equals(definition.getKey()) && suspending.contains(definition.getKey())) {
                requireUnaryLocal(config, definition.getKey(), definition.getValue());
            }
            for (var step : definition.getValue()) {
                step.pipelineReference().ifPresent(target -> {
                    if (reachesAwait(target, definitions, suspending, new HashSet<>())) {
                        requireUnaryLocal(config, definition.getKey(), definition.getValue());
                        requireUnaryLocal(config, target, definitions.getOrDefault(target, List.of()));
                    }
                });
            }
        }
    }

    private boolean reachesAwait(String id, Map<String, List<PipelineTemplateStep>> definitions,
        Set<String> suspending, Set<String> visited) {
        if (!visited.add(id)) return false;
        if (suspending.contains(id)) return true;
        return definitions.getOrDefault(id, List.of()).stream()
            .flatMap(step -> step.pipelineReference().stream())
            .anyMatch(target -> reachesAwait(target, definitions, suspending, visited));
    }

    private void requireUnaryLocal(PipelineTemplateConfig config, String id, List<PipelineTemplateStep> steps) {
        if (!"LOCAL".equalsIgnoreCase(config.transport())
            || steps.stream().anyMatch(step -> !"ONE_TO_ONE".equalsIgnoreCase(step.cardinality()))) {
            throw new IllegalArgumentException("Pipeline definition '" + id
                + "' contains or calls nested Await; its invocation chain requires LOCAL ONE_TO_ONE execution");
        }
    }
}
