package org.pipelineframework.processor.awaitable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateStep;
import org.pipelineframework.processor.mapping.PipelinePlacementModel;
import org.pipelineframework.processor.parser.ParsedPipelineDefinitionCatalog;

/** Validates the complete invocation chain that a durable scalar suspension must restore. */
public final class NestedAwaitValidator {

    /**
     * Validates nested Await chains without an explicit runtime placement model.
     * Under non-LOCAL transport, an invocation chain reaching nested Await is rejected unless
     * resolved physical placement admits local in-segment execution.
     */
    public void validate(PipelineTemplateConfig config, ParsedPipelineDefinitionCatalog parsed) {
        boolean localTransport = "LOCAL".equalsIgnoreCase(config.transport());
        validateWithLocality(config, parsed, id -> localTransport, null);
    }

    /**
     * Validates nested Await chains against the compiler's resolved physical placement model.
     *
     * <p>Every definition in an invocation chain that contains or reaches a nested Await must
     * be unary ({@code ONE_TO_ONE}) and admitted as {@code LOCAL_IN_SEGMENT} by resolved placement.</p>
     */
    public void validate(
        PipelineTemplateConfig config,
        ParsedPipelineDefinitionCatalog parsed,
        PipelinePlacementModel placementModel
    ) {
        boolean localTransport = "LOCAL".equalsIgnoreCase(config.transport());
        Predicate<String> localDefinition = id -> {
            if (localTransport) {
                return true;
            }
            if (placementModel == null) {
                return false;
            }
            return placementModel.placementForDefinition(id)
                .map(PipelinePlacementModel.StepPlacement::localInSegment)
                .orElse(false);
        };
        validateWithLocality(config, parsed, localDefinition, placementModel);
    }

    private void validateWithLocality(
        PipelineTemplateConfig config,
        ParsedPipelineDefinitionCatalog parsed,
        Predicate<String> isLocalDefinition,
        PipelinePlacementModel placementModel
    ) {
        Map<String, List<PipelineTemplateStep>> definitions = new LinkedHashMap<>();
        definitions.put("$root", config.steps());
        config.pipelines().forEach((id, definition) -> definitions.put(id, definition.steps()));
        Set<String> suspending = new HashSet<>();
        if (parsed.rootSteps().stream().anyMatch(step -> step.deferredCompletion().isPresent())) {
            suspending.add("$root");
        }
        parsed.localDefinitions().forEach((id, steps) -> {
            if (steps.stream().anyMatch(step -> step.deferredCompletion().isPresent())) {
                suspending.add(id);
            }
        });
        for (var definition : definitions.entrySet()) {
            if (!"$root".equals(definition.getKey()) && suspending.contains(definition.getKey())) {
                requireUnaryLocal(definition.getKey(), definition.getValue(), isLocalDefinition, placementModel);
            }
            for (var step : definition.getValue()) {
                step.pipelineReference().ifPresent(target -> {
                    if (reachesAwait(target, definitions, suspending, new HashSet<>())) {
                        requireUnaryLocal(definition.getKey(), definition.getValue(), isLocalDefinition, placementModel);
                        requireUnaryLocal(target, definitions.getOrDefault(target, List.of()), isLocalDefinition, placementModel);
                    }
                });
            }
        }
    }

    private boolean reachesAwait(
        String id,
        Map<String, List<PipelineTemplateStep>> definitions,
        Set<String> suspending,
        Set<String> visited
    ) {
        if (!visited.add(id)) {
            return false;
        }
        if (suspending.contains(id)) {
            return true;
        }
        return definitions.getOrDefault(id, List.of()).stream()
            .flatMap(step -> step.pipelineReference().stream())
            .anyMatch(target -> reachesAwait(target, definitions, suspending, visited));
    }

    private void requireUnaryLocal(
        String id,
        List<PipelineTemplateStep> steps,
        Predicate<String> isLocalDefinition,
        PipelinePlacementModel placementModel
    ) {
        boolean unary = steps.stream().allMatch(step -> "ONE_TO_ONE".equalsIgnoreCase(step.cardinality()));
        boolean local = isLocalDefinition.test(id);
        if (!unary || !local) {
            String detail = "";
            if (!local && placementModel != null) {
                detail = placementModel.placementForDefinition(id)
                    .map(p -> " (resolved placement: module='" + p.module() + "', runtime='" + p.runtime()
                        + "', locality=" + p.locality() + ")")
                    .orElse(" (no colocated runtime placement admits local in-segment execution)");
            }
            throw new IllegalArgumentException("Pipeline definition '" + id
                + "' contains or calls nested Await; its invocation chain requires LOCAL ONE_TO_ONE execution" + detail);
        }
    }
}
