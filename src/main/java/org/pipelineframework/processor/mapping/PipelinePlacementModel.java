package org.pipelineframework.processor.mapping;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.pipelineframework.processor.composition.CompiledPipelineLocation;
import org.pipelineframework.processor.ir.PipelineStepModel;

/**
 * Compiler-resolved physical placement and invocation locality model.
 *
 * <p>Separates deployment transport/platform metadata from whether a compiled step or nested/self
 * pipeline invocation runs locally within the current module's execution segment or crosses a
 * deployed boundary.</p>
 */
public final class PipelinePlacementModel {

    public enum InvocationLocality {
        LOCAL_IN_SEGMENT,
        REMOTE_BOUNDARY
    }

    public record StepPlacement(String module, String runtime, InvocationLocality locality) {
        public StepPlacement {
            module = module == null ? "" : module.strip();
            runtime = runtime == null ? "" : runtime.strip();
            locality = Objects.requireNonNull(locality, "locality must not be null");
        }

        public boolean localInSegment() {
            return locality == InvocationLocality.LOCAL_IN_SEGMENT;
        }
    }

    public record InvocationPlacement(
        CompiledPipelineLocation invocationLocation,
        String callerDefinitionId,
        String callsiteId,
        String targetDefinitionId,
        boolean recursive,
        InvocationLocality locality,
        String module,
        String runtime,
        String boundaryReason
    ) {
        public InvocationPlacement {
            invocationLocation = Objects.requireNonNull(invocationLocation, "invocationLocation must not be null");
            callerDefinitionId = Objects.requireNonNull(callerDefinitionId, "callerDefinitionId must not be null");
            callsiteId = Objects.requireNonNull(callsiteId, "callsiteId must not be null");
            targetDefinitionId = Objects.requireNonNull(targetDefinitionId, "targetDefinitionId must not be null");
            locality = Objects.requireNonNull(locality, "locality must not be null");
            module = module == null ? "" : module.strip();
            runtime = runtime == null ? "" : runtime.strip();
            boundaryReason = boundaryReason == null ? "" : boundaryReason.strip();
        }

        public boolean localInSegment() {
            return locality == InvocationLocality.LOCAL_IN_SEGMENT;
        }
    }

    private final Map<PipelineStepModel, StepPlacement> stepPlacements;
    private final Map<String, StepPlacement> stepPlacementsByIdentity;
    private final Map<String, StepPlacement> definitionPlacements;
    private final List<InvocationPlacement> invocationPlacements;
    private final String currentModule;
    private final String currentRuntime;
    private final boolean colocatedSynthetics;

    public PipelinePlacementModel(
        Map<PipelineStepModel, StepPlacement> stepPlacements,
        Map<String, StepPlacement> definitionPlacements,
        List<InvocationPlacement> invocationPlacements,
        String currentModule,
        String currentRuntime,
        boolean colocatedSynthetics
    ) {
        IdentityHashMap<PipelineStepModel, StepPlacement> identityMap = new IdentityHashMap<>();
        Map<String, StepPlacement> byKey = new LinkedHashMap<>();
        if (stepPlacements != null) {
            stepPlacements.forEach((model, placement) -> {
                if (model != null && placement != null) {
                    identityMap.put(model, placement);
                    byKey.putIfAbsent(modelKey(model), placement);
                }
            });
        }
        this.stepPlacements = Collections.unmodifiableMap(identityMap);
        this.stepPlacementsByIdentity = Collections.unmodifiableMap(byKey);
        this.definitionPlacements = definitionPlacements == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(definitionPlacements));
        this.invocationPlacements = invocationPlacements == null ? List.of() : List.copyOf(invocationPlacements);
        this.currentModule = currentModule == null ? "" : currentModule.strip();
        this.currentRuntime = currentRuntime == null ? "" : currentRuntime.strip();
        this.colocatedSynthetics = colocatedSynthetics;
    }

    public static PipelinePlacementModel empty() {
        return new PipelinePlacementModel(Map.of(), Map.of(), List.of(), "", "", false);
    }

    public Optional<StepPlacement> placementForStep(PipelineStepModel model) {
        if (model == null) {
            return Optional.empty();
        }
        StepPlacement exact = stepPlacements.get(model);
        if (exact != null) {
            return Optional.of(exact);
        }
        return Optional.ofNullable(stepPlacementsByIdentity.get(modelKey(model)));
    }

    public boolean isStepLocalInSegment(PipelineStepModel model) {
        return placementForStep(model).map(StepPlacement::localInSegment).orElse(false);
    }

    public Optional<StepPlacement> placementForDefinition(String definitionId) {
        if (definitionId == null || definitionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitionPlacements.get(definitionId));
    }

    public boolean isDefinitionLocalInSegment(String definitionId) {
        return placementForDefinition(definitionId).map(StepPlacement::localInSegment).orElse(false);
    }

    public List<InvocationPlacement> invocationPlacements() {
        return invocationPlacements;
    }

    public boolean hasLocalInvocations() {
        return invocationPlacements.stream().anyMatch(InvocationPlacement::localInSegment);
    }

    public String currentModule() {
        return currentModule;
    }

    public String currentRuntime() {
        return currentRuntime;
    }

    public boolean colocatedSynthetics() {
        return colocatedSynthetics;
    }

    public PipelinePlacementModel withInvocations(List<InvocationPlacement> invocations) {
        return new PipelinePlacementModel(
            stepPlacements, definitionPlacements, invocations, currentModule, currentRuntime, colocatedSynthetics);
    }

    private static String modelKey(PipelineStepModel model) {
        String definition = model.definition() == null ? "$root" : model.definition().logicalId();
        String service = model.serviceClassName() != null
            ? model.serviceClassName().canonicalName()
            : model.servicePackage() + "." + model.serviceName();
        return definition + "|" + model.artifactBindingName() + "|" + service + "|" + model.sideEffect();
    }
}
