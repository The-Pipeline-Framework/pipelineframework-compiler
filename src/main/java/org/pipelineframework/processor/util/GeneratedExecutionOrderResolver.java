package org.pipelineframework.processor.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.composition.PipelineReference;
import org.pipelineframework.processor.ir.AspectPosition;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.PipelineTransport;

/** Resolves compiler-generated execution steps inside one pipeline-definition boundary. */
public final class GeneratedExecutionOrderResolver {

    /**
     * Weaves generated side effects and deferred-completion steps around an authored execution order.
     *
     * <p>An empty result means that the selected definition has no generated execution steps to weave.
     * Callers should retain their authored order in that case.</p>
     */
    public List<String> weave(
        PipelineCompilationContext ctx,
        PipelineReference definition,
        List<String> orderedFunctionalSteps,
        boolean requireGeneratedClient
    ) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(orderedFunctionalSteps, "orderedFunctionalSteps must not be null");

        List<PipelineStepModel> clientModels = ctx.getStepModels().stream()
            .filter(model -> model.deploymentRole() == DeploymentRole.ORCHESTRATOR_CLIENT)
            .filter(model -> definition.equals(model.definition()))
            .toList();
        if (clientModels.stream().noneMatch(PipelineStepModel::sideEffect)
            && clientModels.stream().noneMatch(this::hasDeferredCompletion)) {
            return List.of();
        }

        Map<String, Deque<GeneratedStepGroup>> groupsByFunctionalStep = new LinkedHashMap<>();
        List<String> pendingBefore = new ArrayList<>();
        GeneratedStepGroup current = null;
        for (PipelineStepModel model : clientModels) {
            if (model.sideEffect()) {
                String sideEffect = ClientStepClassNames.className(model, ctx.getTransportMode());
                if (model.aspectPosition().filter(position -> position == AspectPosition.BEFORE_STEP).isPresent()
                    || current == null) {
                    pendingBefore.add(sideEffect);
                } else {
                    current.after().add(sideEffect);
                }
                continue;
            }

            String orderIdentity = RuntimeStepClassNames.className(
                model, ctx.getTransportMode(), requireGeneratedClient);
            String operationStep = hasDeferredCompletion(model)
                ? ordinaryOperationClientStepName(model, ctx.getTransportMode())
                : orderIdentity;
            String completionStep = hasDeferredCompletion(model) ? orderIdentity : "";
            current = new GeneratedStepGroup(
                List.copyOf(pendingBefore), operationStep, new ArrayList<>(), completionStep);
            pendingBefore.clear();
            groupsByFunctionalStep.computeIfAbsent(orderIdentity, ignored -> new ArrayDeque<>()).add(current);
        }

        if (!pendingBefore.isEmpty()) {
            if (groupsByFunctionalStep.isEmpty()) {
                return List.of();
            }
            throw new IllegalStateException(
                "Generated aspect order ends with before-step side effects without a functional step");
        }

        List<String> expanded = new ArrayList<>();
        for (String functionalStep : orderedFunctionalSteps) {
            Deque<GeneratedStepGroup> groups = groupsByFunctionalStep.get(functionalStep);
            if (groups == null || groups.isEmpty()) {
                expanded.add(functionalStep);
                continue;
            }
            GeneratedStepGroup group = groups.removeFirst();
            expanded.addAll(group.before());
            expanded.add(group.operation());
            expanded.addAll(group.after());
            if (!group.completion().isBlank()) {
                expanded.add(group.completion());
            }
        }
        return List.copyOf(expanded);
    }

    private boolean hasDeferredCompletion(PipelineStepModel model) {
        return model.enabledTargets().contains(GenerationTarget.DEFERRED_COMPLETION_STEP);
    }

    private String ordinaryOperationClientStepName(PipelineStepModel model, PipelineTransport transport) {
        PipelineTransport effectiveTransport = transport == null ? PipelineTransport.GRPC : transport;
        String generatedName = model.generatedName();
        String baseName = generatedName != null && generatedName.endsWith("Service")
            ? generatedName.substring(0, generatedName.length() - "Service".length())
            : generatedName;
        return model.servicePackage() + ".pipeline." + baseName + effectiveTransport.clientStepSuffix();
    }

    private record GeneratedStepGroup(
        List<String> before,
        String operation,
        List<String> after,
        String completion
    ) {
    }
}
