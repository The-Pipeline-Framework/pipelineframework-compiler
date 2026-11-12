package org.pipelineframework.processor.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.pipelineframework.config.template.PipelineTemplateDialect;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.composition.LocalPipelineInvocationClassName;
import org.pipelineframework.processor.composition.PipelineReference;
import org.pipelineframework.processor.ir.AspectPosition;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.PipelineStepModel;

/** Canonical value boundaries of the exact compiler-selected root execution order. */
final class PipelineOrderBoundaryMetadata {
    private static final PipelineReference ROOT = new PipelineReference("$root");

    record Boundary(String stepClass, String inputTypeId, String outputTypeId) {}

    record DefinitionBoundary(String invocationClass, String definitionId, String callsiteId,
                              List<Boundary> stepBoundaries) {}

    static Optional<List<DefinitionBoundary>> projectDefinitions(PipelineCompilationContext ctx) {
        var result = new ArrayList<DefinitionBoundary>();
        if (ctx.getResolvedPipelineDefinitionGraph().isPresent()) {
            var expected = ctx.getResolvedPipelineDefinitionGraph().orElseThrow().invocationBindings().stream()
                .map(binding -> LocalPipelineInvocationClassName.canonicalName(
                    ctx.getPipelineTemplateConfig().basePackage(), binding.invocationLocation()))
                .collect(java.util.stream.Collectors.toSet());
            var recorded = ctx.generatedInvocationOrders().stream()
                .map(PipelineCompilationContext.GeneratedInvocationOrder::invocationClass)
                .collect(java.util.stream.Collectors.toSet());
            if (!expected.equals(recorded)) return Optional.empty();
        }
        for (var invocation : ctx.generatedInvocationOrders()) {
            var boundaries = project(ctx, new PipelineReference(invocation.definitionId()), invocation.order(), true);
            if (boundaries.isEmpty()) return Optional.empty();
            result.add(new DefinitionBoundary(invocation.invocationClass(), invocation.definitionId(),
                invocation.callsiteId(), boundaries.orElseThrow()));
        }
        return Optional.of(List.copyOf(result));
    }

    /** Missing legacy facts leave the additive metadata absent; they never become guessed identities. */
    static Optional<List<Boundary>> project(PipelineCompilationContext ctx, List<String> order) {
        return project(ctx, ROOT, order, ctx.isOrchestratorGenerated());
    }

    private static Optional<List<Boundary>> project(PipelineCompilationContext ctx, PipelineReference definition,
                                                   List<String> order, boolean requireGeneratedClient) {
        var template = ctx.getPipelineTemplateConfig();
        if (template == null || template.dialect() != PipelineTemplateDialect.V3) return Optional.empty();
        Map<String, Boundary> boundaries = new LinkedHashMap<>();
        var models = new GeneratedExecutionOrderResolver().executionModels(ctx, definition);
        for (var model : models) {
            String input = model.inputMapping().canonicalTypeName().orElse(null);
            String output = model.outputMapping().canonicalTypeName().orElse(null);
            if (model.sideEffect()) {
                if (model.observationProjection().isPresent()) {
                    var projection = model.observationProjection().orElseThrow();
                    var owners = models.stream().filter(candidate -> !candidate.sideEffect()
                        && candidate.serviceName().equals(projection.ownerServiceName())).toList();
                    if (owners.size() != 1) return Optional.empty();
                    var owner = owners.getFirst();
                    input = (model.aspectPosition().orElseThrow() == AspectPosition.BEFORE_STEP
                        ? owner.inputMapping() : owner.outputMapping()).canonicalTypeName().orElse(null);
                }
                output = input; // Observation projects a value for the observer, but preserves its carrier.
            }
            String runtimeClass = model.sideEffect() ? ClientStepClassNames.className(model, ctx.getTransportMode())
                : RuntimeStepClassNames.className(model, ctx.getTransportMode(), requireGeneratedClient);
            if (model.enabledTargets().contains(GenerationTarget.DEFERRED_COMPLETION_STEP)) {
                if (!put(boundaries, ClientStepClassNames.operationClassName(model, ctx.getTransportMode()), input, output))
                    return Optional.empty();
                var descriptor = ctx.awaitRuntimeDescriptor(model.serviceName());
                if (descriptor.isEmpty()) return Optional.empty();
                input = (String) descriptor.orElseThrow().get("canonicalInputTypeId");
                output = (String) descriptor.orElseThrow().get("canonicalOutputTypeId");
            } else if (model.deferredCompletionSelection().isPresent()) {
                // Callback Commands perform their operation and completion in one generated step.
                var descriptor = ctx.awaitRuntimeDescriptor(model.serviceName());
                if (descriptor.isEmpty()) return Optional.empty();
                input = (String) descriptor.orElseThrow().get("canonicalInputTypeId");
                output = (String) descriptor.orElseThrow().get("canonicalOutputTypeId");
            }
            if (!put(boundaries, runtimeClass, input, output)) return Optional.empty();
        }
        if (ctx.getResolvedPipelineDefinitionGraph().isPresent()) {
            var graph = ctx.getResolvedPipelineDefinitionGraph().orElseThrow();
            for (var binding : graph.invocationBindings()) {
                var location = binding.invocationLocation();
                if (!definition.equals(location.definitionLocalLocation().definition())) continue;
                var callsite = graph.definitions().get(definition).steps().stream()
                    .filter(step -> step.localStepId().equals(location.definitionLocalLocation().localStepId()))
                    .findFirst().orElseThrow();
                if (!put(boundaries, LocalPipelineInvocationClassName.canonicalName(template.basePackage(), location),
                    callsite.inputContractId(), callsite.outputContractId())) return Optional.empty();
            }
        }
        var result = new ArrayList<Boundary>();
        for (String stepClass : order) {
            Boundary boundary = boundaries.get(stepClass);
            if (boundary == null) return Optional.empty();
            result.add(boundary);
        }
        return Optional.of(List.copyOf(result));
    }

    private static boolean put(Map<String, Boundary> boundaries, String stepClass, String input, String output) {
        if (input == null || input.isBlank() || output == null || output.isBlank()) return false;
        var boundary = new Boundary(stepClass, input, output);
        var previous = boundaries.putIfAbsent(stepClass, boundary);
        return previous == null || previous.equals(boundary);
    }
}
