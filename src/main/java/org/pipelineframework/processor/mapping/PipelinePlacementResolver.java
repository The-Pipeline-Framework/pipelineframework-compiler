package org.pipelineframework.processor.mapping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.pipelineframework.config.PlatformMode;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.composition.PipelineDefinition;
import org.pipelineframework.processor.composition.PipelineDefinitionStep;
import org.pipelineframework.processor.composition.PipelineInvocationBinding;
import org.pipelineframework.processor.composition.PipelineReference;
import org.pipelineframework.processor.composition.ResolvedPipelineDefinitionGraph;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.mapping.PipelinePlacementModel.InvocationLocality;
import org.pipelineframework.processor.mapping.PipelinePlacementModel.InvocationPlacement;
import org.pipelineframework.processor.mapping.PipelinePlacementModel.StepPlacement;

/**
 * Resolves physical step and invocation placement from transport, platform, runtime mapping, and module identity.
 *
 * <p>Colocation rule:</p>
 * <ul>
 *   <li>{@code transport: LOCAL} admits all steps and nested/self invocations as {@code LOCAL_IN_SEGMENT}.</li>
 *   <li>Under {@code REST}/{@code GRPC}, {@code pipeline.runtime.yaml} admits {@code LOCAL_IN_SEGMENT} when
 *       the resolved {@code (module, runtime)} matches the current compilation module and runtime under
 *       {@code PIPELINE_RUNTIME}, or under {@code MONOLITH} when {@code platform: FUNCTION}.</li>
 *   <li>{@code COMPUTE} {@code MONOLITH} preserves existing loopback transport behavior; {@code MODULAR} and
 *       unmapped {@code REST}/{@code GRPC} resolve to {@code REMOTE_BOUNDARY}.</li>
 * </ul>
 */
public final class PipelinePlacementResolver {

    public PipelinePlacementModel resolve(PipelineCompilationContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        PipelineTransport transport = Objects.requireNonNullElse(ctx.getTransportMode(), PipelineTransport.GRPC);
        PlatformMode platform = Objects.requireNonNullElse(ctx.getPlatformMode(), PlatformMode.COMPUTE);
        PipelineRuntimeMapping mapping = ctx.getRuntimeMapping();
        PipelineRuntimeMappingResolution resolution = ctx.getRuntimeMappingResolution();
        boolean orchestratorModule = isOrchestratorModule(ctx);
        String currentModule = normalize(ctx.getModuleName());
        String currentRuntime = resolveCurrentRuntime(currentModule, mapping, resolution, transport);

        IdentityHashMap<PipelineStepModel, StepPlacement> stepPlacements = new IdentityHashMap<>();
        Map<String, List<StepPlacement>> stepsByDefinition = new LinkedHashMap<>();

        List<PipelineStepModel> allModels = new ArrayList<>();
        IdentityHashMap<PipelineStepModel, Boolean> seen = new IdentityHashMap<>();
        if (ctx.getStepModels() != null) {
            for (PipelineStepModel model : ctx.getStepModels()) {
                if (model != null && seen.putIfAbsent(model, Boolean.TRUE) == null) {
                    allModels.add(model);
                }
            }
        }
        if (resolution != null && resolution.moduleAssignments() != null) {
            for (PipelineStepModel model : resolution.moduleAssignments().keySet()) {
                if (model != null && seen.putIfAbsent(model, Boolean.TRUE) == null) {
                    allModels.add(model);
                }
            }
        }

        for (PipelineStepModel model : allModels) {
            StepPlacement placement = resolveStepPlacement(
                model, transport, platform, mapping, resolution, orchestratorModule, currentModule, currentRuntime);
            stepPlacements.put(model, placement);
            if (!model.sideEffect()) {
                String defId = model.definition() == null ? "$root" : model.definition().logicalId();
                stepsByDefinition.computeIfAbsent(defId, ignored -> new ArrayList<>()).add(placement);
            }
        }

        Map<String, StepPlacement> definitionPlacements = new LinkedHashMap<>();
        stepsByDefinition.forEach((defId, placements) ->
            definitionPlacements.put(defId, aggregateDefinitionPlacement(placements, currentModule, currentRuntime, transport)));

        boolean colocatedLocalSynthetics = isColocatedLocalSynthetics(
            transport, platform, mapping, resolution, orchestratorModule, currentModule, currentRuntime);
        return new PipelinePlacementModel(
            stepPlacements, definitionPlacements, List.of(), currentModule, currentRuntime, colocatedLocalSynthetics);
    }

    /** Enriches a base placement model with resolved locality for every linked invocation binding. */
    public PipelinePlacementModel resolveInvocations(
        PipelineCompilationContext ctx,
        PipelinePlacementModel base,
        ResolvedPipelineDefinitionGraph graph
    ) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(graph, "graph must not be null");

        PipelineTransport transport = Objects.requireNonNullElse(ctx.getTransportMode(), PipelineTransport.GRPC);
        PipelineRuntimeMapping mapping = ctx.getRuntimeMapping();
        List<InvocationPlacement> invocations = new ArrayList<>();

        for (PipelineInvocationBinding binding : graph.invocationBindings()) {
            var local = binding.invocationLocation().definitionLocalLocation();
            String callerId = local.definition().logicalId();
            String callsiteId = local.localStepId();
            String targetId = binding.target().logicalId();

            InvocationCheck check = evaluateInvocation(
                transport, mapping, base, graph, callerId, targetId);
            invocations.add(new InvocationPlacement(
                binding.invocationLocation(),
                callerId,
                callsiteId,
                targetId,
                binding.recursive(),
                check.locality(),
                check.module(),
                check.runtime(),
                check.reason()));
        }
        return base.withInvocations(invocations);
    }

    /**
     * Determines whether this compilation owns orchestrator generation (works in both early
     * extraction phases and post-semantic-analysis phases).
     */
    public static boolean isOrchestratorModule(PipelineCompilationContext ctx) {
        if (ctx == null) {
            return false;
        }
        if (ctx.isOrchestratorGenerated()) {
            return true;
        }
        if (ctx.getSourceInventory() != null
            && ctx.getSourceInventory().pipelineOrchestratorElements() != null
            && !ctx.getSourceInventory().pipelineOrchestratorElements().isEmpty()) {
            return true;
        }
        if (ctx.getCompilerOptions() != null) {
            String option = ctx.getCompilerOptions().asMap().get("pipeline.orchestrator.generate");
            if (option != null && ("true".equalsIgnoreCase(option.trim()) || "1".equals(option.trim()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Quick check for early phases (before PipelineRuntimeMappingPhase) to determine whether
     * the compilation configuration admits colocated in-segment execution for the current module.
     */
    public static boolean isModuleColocated(PipelineCompilationContext ctx) {
        if (ctx == null) {
            return false;
        }
        if (ctx.isTransportModeLocal()) {
            return true;
        }
        if (!isOrchestratorModule(ctx)) {
            return false;
        }
        PipelineRuntimeMapping mapping = ctx.getRuntimeMapping();
        if (mapping == null) {
            return false;
        }
        String currentModule = normalize(ctx.getModuleName());
        if (currentModule.isEmpty()) {
            return false;
        }
        PlatformMode platform = Objects.requireNonNullElse(ctx.getPlatformMode(), PlatformMode.COMPUTE);
        if (mapping.layout() == PipelineRuntimeMapping.Layout.MONOLITH) {
            return platform == PlatformMode.FUNCTION;
        }
        if (mapping.layout() == PipelineRuntimeMapping.Layout.PIPELINE_RUNTIME) {
            String defaultModule = normalize(mapping.defaults().module());
            if (!defaultModule.equals(currentModule)) {
                return false;
            }
            String currentRuntime = resolveCurrentRuntime(currentModule, mapping, ctx.getRuntimeMappingResolution(),
                Objects.requireNonNullElse(ctx.getTransportMode(), PipelineTransport.GRPC));
            return !currentRuntime.isEmpty();
        }
        return false;
    }

    /** Checks whether synthetic side-effect beans are colocated in the current compilation module. */
    public static boolean hasColocatedSynthetics(PipelineCompilationContext ctx) {
        if (ctx == null) {
            return false;
        }
        if (ctx.isTransportModeLocal()) {
            return true;
        }
        PipelineRuntimeMapping mapping = ctx.getRuntimeMapping();
        if (mapping == null) {
            return false;
        }
        if (mapping.layout() == PipelineRuntimeMapping.Layout.MONOLITH) {
            return true;
        }
        String currentModule = normalize(ctx.getModuleName());
        if (currentModule.isEmpty()) {
            return false;
        }
        String currentRuntime = resolveCurrentRuntime(currentModule, mapping, ctx.getRuntimeMappingResolution(),
            Objects.requireNonNullElse(ctx.getTransportMode(), PipelineTransport.GRPC));
        return isColocatedSyntheticsInModule(
            Objects.requireNonNullElse(ctx.getTransportMode(), PipelineTransport.GRPC),
            mapping, ctx.getRuntimeMappingResolution(), currentModule, currentRuntime);
    }

    /** Checks whether synthetic side-effect steps execute via local in-segment client steps. */
    public static boolean hasLocalInSegmentSynthetics(PipelineCompilationContext ctx) {
        if (ctx == null) {
            return false;
        }
        PipelineTransport transport = Objects.requireNonNullElse(ctx.getTransportMode(), PipelineTransport.GRPC);
        PlatformMode platform = Objects.requireNonNullElse(ctx.getPlatformMode(), PlatformMode.COMPUTE);
        PipelineRuntimeMapping mapping = ctx.getRuntimeMapping();
        String currentModule = normalize(ctx.getModuleName());
        String currentRuntime = resolveCurrentRuntime(currentModule, mapping, ctx.getRuntimeMappingResolution(), transport);
        return isColocatedLocalSynthetics(
            transport, platform, mapping, ctx.getRuntimeMappingResolution(),
            isOrchestratorModule(ctx), currentModule, currentRuntime);
    }

    private StepPlacement resolveStepPlacement(
        PipelineStepModel model,
        PipelineTransport transport,
        PlatformMode platform,
        PipelineRuntimeMapping mapping,
        PipelineRuntimeMappingResolution resolution,
        boolean orchestratorModule,
        String currentModule,
        String currentRuntime
    ) {
        if (transport == PipelineTransport.LOCAL) {
            String module = resolution != null
                ? resolution.moduleForModel(model).orElse(currentModule)
                : currentModule;
            String runtime = resolution != null && !module.isEmpty()
                ? resolution.runtimeForModule(module, mapping).orElse(currentRuntime)
                : currentRuntime;
            return new StepPlacement(module, runtime, InvocationLocality.LOCAL_IN_SEGMENT);
        }
        if (mapping == null) {
            return new StepPlacement(currentModule, currentRuntime, InvocationLocality.REMOTE_BOUNDARY);
        }
        String assignedModule = resolution != null
            ? resolution.moduleForModel(model).orElse("")
            : fallbackModule(model, mapping, currentModule);
        String assignedRuntime = resolution != null
            ? resolution.runtimeForModule(assignedModule, mapping).orElse("")
            : resolveRuntime(assignedModule, mapping);

        boolean sameUnit = orchestratorModule
            && !currentModule.isEmpty()
            && assignedModule.equals(currentModule)
            && !currentRuntime.isEmpty()
            && assignedRuntime.equals(currentRuntime);

        boolean layoutAdmitsLocal = switch (mapping.layout()) {
            case PIPELINE_RUNTIME -> sameUnit;
            case MONOLITH -> sameUnit && platform == PlatformMode.FUNCTION;
            case MODULAR -> false;
        };

        return new StepPlacement(
            assignedModule,
            assignedRuntime,
            layoutAdmitsLocal ? InvocationLocality.LOCAL_IN_SEGMENT : InvocationLocality.REMOTE_BOUNDARY);
    }

    private InvocationCheck evaluateInvocation(
        PipelineTransport transport,
        PipelineRuntimeMapping mapping,
        PipelinePlacementModel base,
        ResolvedPipelineDefinitionGraph graph,
        String callerId,
        String targetId
    ) {
        if (transport == PipelineTransport.LOCAL) {
            return new InvocationCheck(
                InvocationLocality.LOCAL_IN_SEGMENT,
                base.currentModule(),
                base.currentRuntime().isEmpty() ? "local" : base.currentRuntime(),
                "");
        }
        if (mapping == null) {
            return new InvocationCheck(
                InvocationLocality.REMOTE_BOUNDARY,
                base.currentModule(),
                base.currentRuntime(),
                "no pipeline.runtime.yaml placement colocates caller '" + callerId
                    + "' and target '" + targetId + "' under pipeline.transport=" + transport.name());
        }
        if (base.currentModule().isEmpty()) {
            return new InvocationCheck(
                InvocationLocality.REMOTE_BOUNDARY,
                "",
                base.currentRuntime(),
                "pipeline.module is required to verify colocated placement for '" + callerId + "' -> '" + targetId + "'");
        }

        // Verify caller and all transitively reachable definitions from target resolve to LOCAL_IN_SEGMENT
        // in the exact same (module, runtime).
        Optional<String> callerMismatch = checkDefinitionLocal(callerId, base, graph, new HashSet<>(), false);
        if (callerMismatch.isPresent()) {
            return new InvocationCheck(
                InvocationLocality.REMOTE_BOUNDARY,
                base.currentModule(),
                base.currentRuntime(),
                callerMismatch.orElseThrow());
        }
        Optional<String> targetMismatch = checkDefinitionLocal(targetId, base, graph, new HashSet<>(), true);
        if (targetMismatch.isPresent()) {
            return new InvocationCheck(
                InvocationLocality.REMOTE_BOUNDARY,
                base.currentModule(),
                base.currentRuntime(),
                targetMismatch.orElseThrow());
        }
        return new InvocationCheck(
            InvocationLocality.LOCAL_IN_SEGMENT,
            base.currentModule(),
            base.currentRuntime(),
            "");
    }

    private Optional<String> checkDefinitionLocal(
        String definitionId,
        PipelinePlacementModel base,
        ResolvedPipelineDefinitionGraph graph,
        Set<String> visited,
        boolean traverseCallees
    ) {
        if (!visited.add(definitionId)) {
            return Optional.empty();
        }
        Optional<StepPlacement> defPlacement = base.placementForDefinition(definitionId);
        if (defPlacement.isPresent()) {
            StepPlacement placement = defPlacement.orElseThrow();
            if (!placement.localInSegment()
                || !placement.module().equals(base.currentModule())
                || !placement.runtime().equals(base.currentRuntime())) {
                return Optional.of("definition '" + definitionId + "' resolves to module '"
                    + placement.module() + "' (runtime '" + placement.runtime() + "', locality " + placement.locality()
                    + ") instead of current segment module '" + base.currentModule()
                    + "' (runtime '" + base.currentRuntime() + "')");
            }
        }
        if (!traverseCallees) {
            return Optional.empty();
        }
        PipelineDefinition def = graph.definitions().get(new PipelineReference(definitionId));
        if (def == null) {
            return Optional.empty();
        }
        for (PipelineDefinitionStep step : def.steps()) {
            if (step.pipelineReference().isPresent()) {
                Optional<String> nested = checkDefinitionLocal(
                    step.pipelineReference().orElseThrow().logicalId(), base, graph, visited, true);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private static StepPlacement aggregateDefinitionPlacement(
        List<StepPlacement> placements,
        String currentModule,
        String currentRuntime,
        PipelineTransport transport
    ) {
        if (placements == null || placements.isEmpty()) {
            return new StepPlacement(
                currentModule,
                currentRuntime,
                transport == PipelineTransport.LOCAL
                    ? InvocationLocality.LOCAL_IN_SEGMENT
                    : InvocationLocality.REMOTE_BOUNDARY);
        }
        StepPlacement first = placements.getFirst();
        for (StepPlacement candidate : placements) {
            if (!candidate.localInSegment()
                || !candidate.module().equals(first.module())
                || !candidate.runtime().equals(first.runtime())) {
                return new StepPlacement(
                    candidate.module(),
                    candidate.runtime(),
                    InvocationLocality.REMOTE_BOUNDARY);
            }
        }
        return first;
    }

    private static boolean isColocatedSyntheticsInModule(
        PipelineTransport transport,
        PipelineRuntimeMapping mapping,
        PipelineRuntimeMappingResolution resolution,
        String currentModule,
        String currentRuntime
    ) {
        if (transport == PipelineTransport.LOCAL) {
            return true;
        }
        if (mapping == null) {
            return false;
        }
        if (mapping.layout() == PipelineRuntimeMapping.Layout.MONOLITH) {
            return true;
        }
        if (mapping.layout() == PipelineRuntimeMapping.Layout.PIPELINE_RUNTIME && !currentModule.isEmpty()) {
            String syntheticModule = normalize(mapping.defaults().synthetic().module());
            if (syntheticModule.equals(currentModule)) {
                String syntheticRuntime = resolution != null
                    ? resolution.runtimeForModule(syntheticModule, mapping).orElse("")
                    : resolveRuntime(syntheticModule, mapping);
                return !currentRuntime.isEmpty() && currentRuntime.equals(syntheticRuntime);
            }
        }
        return false;
    }

    private static boolean isColocatedLocalSynthetics(
        PipelineTransport transport,
        PlatformMode platform,
        PipelineRuntimeMapping mapping,
        PipelineRuntimeMappingResolution resolution,
        boolean orchestratorModule,
        String currentModule,
        String currentRuntime
    ) {
        if (transport == PipelineTransport.LOCAL) {
            return true;
        }
        if (!orchestratorModule || mapping == null) {
            return false;
        }
        if (mapping.layout() == PipelineRuntimeMapping.Layout.MONOLITH) {
            return platform == PlatformMode.FUNCTION;
        }
        return isColocatedSyntheticsInModule(transport, mapping, resolution, currentModule, currentRuntime);
    }

    private static String fallbackModule(
        PipelineStepModel model,
        PipelineRuntimeMapping mapping,
        String currentModule
    ) {
        if (mapping.layout() == PipelineRuntimeMapping.Layout.MONOLITH) {
            if (!currentModule.isEmpty()) {
                return currentModule;
            }
            return normalize(mapping.defaults().module());
        }
        if (model.sideEffect()) {
            return normalize(mapping.defaults().synthetic().module());
        }
        String defaultModule = normalize(mapping.defaults().module());
        return "per-step".equalsIgnoreCase(defaultModule) ? "" : defaultModule;
    }

    private static String resolveCurrentRuntime(
        String currentModule,
        PipelineRuntimeMapping mapping,
        PipelineRuntimeMappingResolution resolution,
        PipelineTransport transport
    ) {
        if (mapping == null) {
            return transport == PipelineTransport.LOCAL ? "local" : "";
        }
        if (resolution != null && !currentModule.isEmpty()) {
            Optional<String> fromResolution = resolution.runtimeForModule(currentModule, mapping);
            if (fromResolution.isPresent()) {
                return fromResolution.orElseThrow();
            }
        }
        return resolveRuntime(currentModule, mapping);
    }

    private static String resolveRuntime(String module, PipelineRuntimeMapping mapping) {
        if (mapping == null) {
            return "";
        }
        if (module != null && !module.isBlank()) {
            String mapped = mapping.modules().get(module);
            if (mapped != null && !mapped.isBlank()) {
                return mapped.strip();
            }
        }
        return normalize(mapping.defaults().runtime());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private record InvocationCheck(InvocationLocality locality, String module, String runtime, String reason) {
    }
}
