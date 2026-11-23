package org.pipelineframework.processor.composition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateDialect;
import org.pipelineframework.config.template.PipelineTemplateTypeModel;
import org.pipelineframework.orchestrator.release.PipelinePlacementDescriptor;
import org.pipelineframework.orchestrator.release.PipelinePlacementDescriptor.ExecutionSegment;
import org.pipelineframework.orchestrator.release.PipelinePlacementDescriptor.InvocationPlacement;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.ir.DeferredCompletionDefinition;
import org.pipelineframework.processor.ir.StepDefinition;
import org.pipelineframework.processor.mapping.PipelinePlacementModel;
import org.pipelineframework.processor.routing.PipelineBranchingPlan;

/**
 * Derives the real branch- and recursion-aware execution-segment graph from compiled definitions,
 * branch routing plans, actual reachable Await boundaries, and resolved physical placement.
 */
public final class ExecutionSegmentGraphDeriver {

    private static final String ROOT_ID = "$root";
    private static final String WILDCARD_TYPE = "*";

    public PipelinePlacementDescriptor derive(
        PipelineCompilationContext ctx,
        PipelinePlacementModel placementModel
    ) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        Objects.requireNonNull(placementModel, "placementModel must not be null");
        PipelineTemplateConfig template = ctx.getPipelineTemplateConfig();
        if (template == null || template.dialect() != PipelineTemplateDialect.V3) {
            return PipelinePlacementDescriptor.empty();
        }
        if (ctx.getResolvedPipelineDefinitionGraph().isEmpty() && ctx.getRuntimeMapping() == null) {
            return PipelinePlacementDescriptor.empty();
        }
        List<StepDefinition> rootParsed = ctx.getParsedPipelineDefinitionCatalog().rootSteps();
        if (rootParsed == null || rootParsed.isEmpty()) {
            return PipelinePlacementDescriptor.empty();
        }

        Map<String, List<StepNode>> definitions = buildDefinitions(ctx, template);
        if (!definitions.containsKey(ROOT_ID) || definitions.get(ROOT_ID).isEmpty()) {
            return PipelinePlacementDescriptor.empty();
        }

        List<InvocationPlacement> invocations = placementModel.invocationPlacements().stream()
            .map(invocation -> new InvocationPlacement(
                invocation.callerDefinitionId(),
                invocation.callsiteId(),
                invocation.targetDefinitionId(),
                invocation.recursive(),
                invocation.locality().name(),
                invocation.module(),
                invocation.runtime()))
            .toList();

        Set<String> rootInputTypes = resolveRootInputTypes(template, definitions.get(ROOT_ID));

        Map<String, AwaitOrigin> discoveredAwaits = new LinkedHashMap<>();
        Deque<SegmentWork> queue = new ArrayDeque<>();
        Set<String> queuedSegments = new HashSet<>();

        SegmentWork entryWork = new SegmentWork(
            "entry",
            PipelinePlacementDescriptor.ROOT_ENTRY,
            "",
            ROOT_ID,
            0,
            rootInputTypes,
            List.of(),
            List.of());
        queue.add(entryWork);
        queuedSegments.add(entryWork.segmentId());

        List<ExecutionSegment> segments = new ArrayList<>();
        while (!queue.isEmpty()) {
            SegmentWork work = queue.removeFirst();
            SegmentTrace trace = traceSegment(work, definitions, discoveredAwaits);
            for (String awaitId : trace.nextAwaitBoundaries()) {
                String resumeSegmentId = "resume:" + awaitId;
                if (queuedSegments.add(resumeSegmentId)) {
                    AwaitOrigin origin = discoveredAwaits.get(awaitId);
                    if (origin != null) {
                        queue.addLast(new SegmentWork(
                            resumeSegmentId,
                            PipelinePlacementDescriptor.AWAIT_RESUME,
                            awaitId,
                            origin.definitionId(),
                            origin.stepIndex() + 1,
                            origin.resumedTypes(),
                            origin.callerStack(),
                            origin.callsiteChain()));
                    }
                }
            }
            var defPlacement = placementModel.placementForDefinition(work.startDefinitionId());
            String module = defPlacement.map(PipelinePlacementModel.StepPlacement::module)
                .filter(value -> !value.isBlank())
                .orElse(placementModel.currentModule());
            String runtime = defPlacement.map(PipelinePlacementModel.StepPlacement::runtime)
                .filter(value -> !value.isBlank())
                .orElse(placementModel.currentRuntime());
            segments.add(new ExecutionSegment(
                work.segmentId(),
                work.startKind(),
                work.resumeAwaitBoundaryId(),
                module,
                runtime,
                trace.canCompleteRoot(),
                List.copyOf(trace.reachableDefinitions()),
                List.copyOf(trace.nextAwaitBoundaries())));
        }

        if (invocations.isEmpty() && segments.isEmpty()) {
            return PipelinePlacementDescriptor.empty();
        }
        return new PipelinePlacementDescriptor(
            PipelinePlacementDescriptor.CURRENT_SCHEMA_VERSION,
            invocations,
            segments);
    }

    private Map<String, List<StepNode>> buildDefinitions(
        PipelineCompilationContext ctx,
        PipelineTemplateConfig template
    ) {
        Map<String, List<StepNode>> result = new LinkedHashMap<>();
        PipelineTemplateTypeModel typeModel = template.typeModel();
        PipelineBranchingPlan rootPlan = ctx.getBranchingPlan() == null
            ? PipelineBranchingPlan.disabled()
            : ctx.getBranchingPlan();
        result.put(ROOT_ID, buildDefinitionSteps(
            ROOT_ID, ctx.getParsedPipelineDefinitionCatalog().rootSteps(), rootPlan, typeModel));

        ctx.getParsedPipelineDefinitionCatalog().localDefinitions().forEach((defId, steps) -> {
            PipelineBranchingPlan plan = ctx.getLocalDefinitionBranchingPlans()
                .getOrDefault(new PipelineReference(defId), PipelineBranchingPlan.disabled());
            result.put(defId, buildDefinitionSteps(defId, steps, plan, typeModel));
        });
        return result;
    }

    private List<StepNode> buildDefinitionSteps(
        String definitionId,
        List<StepDefinition> steps,
        PipelineBranchingPlan plan,
        PipelineTemplateTypeModel typeModel
    ) {
        List<StepNode> nodes = new ArrayList<>(steps.size());
        boolean branchAware = plan != null && plan.branchAware() && plan.steps().size() == steps.size();
        for (int i = 0; i < steps.size(); i++) {
            StepDefinition step = steps.get(i);
            Set<String> accepted;
            Set<String> produced;
            boolean terminal;
            if (branchAware) {
                PipelineBranchingPlan.BranchStep branchStep = plan.steps().get(i);
                accepted = new LinkedHashSet<>(branchStep.acceptedContractTypes());
                produced = new LinkedHashSet<>(branchStep.producedLeafContractTypes());
                terminal = branchStep.terminal();
            } else {
                accepted = Set.of(WILDCARD_TYPE);
                produced = Set.of(WILDCARD_TYPE);
                terminal = i == steps.size() - 1;
            }
            Optional<String> target = step.pipelineReference();
            boolean recursive = target.filter(definitionId::equals).isPresent();
            Optional<DeferredCompletionDefinition> await = step.deferredCompletion();
            Set<String> awaitedResumedTypes = Set.of();
            Set<String> nonAwaitedPassThroughTypes = Set.of();
            if (await.isPresent()) {
                DeferredCompletionDefinition completion = await.orElseThrow();
                if (!completion.accepts().isEmpty() && completion.selectedOutputType().isPresent()) {
                    Set<String> resumed = concreteTypes(typeModel, completion.selectedOutputType().orElseThrow());
                    awaitedResumedTypes = resumed.isEmpty() ? produced : resumed;
                    Set<String> passThrough = new LinkedHashSet<>(produced);
                    passThrough.removeAll(awaitedResumedTypes);
                    nonAwaitedPassThroughTypes = Set.copyOf(passThrough);
                } else {
                    awaitedResumedTypes = produced;
                }
            }
            nodes.add(new StepNode(
                definitionId,
                i,
                step.name(),
                target,
                recursive,
                await.isPresent(),
                awaitedResumedTypes,
                nonAwaitedPassThroughTypes,
                Set.copyOf(accepted),
                Set.copyOf(produced),
                terminal,
                branchAware));
        }
        return List.copyOf(nodes);
    }

    private Set<String> resolveRootInputTypes(PipelineTemplateConfig template, List<StepNode> rootSteps) {
        if (!rootSteps.getFirst().branchAware()) {
            return Set.of(WILDCARD_TYPE);
        }
        String rootInput = template.inputContract();
        if (rootInput == null || rootInput.isBlank()) {
            rootInput = template.steps().getFirst().inputTypeName();
        }
        Set<String> concrete = concreteTypes(template.typeModel(), rootInput);
        return concrete.isEmpty() ? rootSteps.getFirst().acceptedTypes() : concrete;
    }

    private Set<String> concreteTypes(PipelineTemplateTypeModel typeModel, String typeName) {
        if (typeModel == null || typeName == null || typeName.isBlank()) {
            return Set.of();
        }
        try {
            return new LinkedHashSet<>(new TreeSet<>(typeModel.concreteTypeNames(typeName)));
        } catch (IllegalArgumentException ignored) {
            return Set.of();
        }
    }

    private SegmentTrace traceSegment(
        SegmentWork work,
        Map<String, List<StepNode>> definitions,
        Map<String, AwaitOrigin> discoveredAwaits
    ) {
        Set<String> reachableDefinitions = new LinkedHashSet<>();
        Set<String> nextAwaits = new LinkedHashSet<>();
        boolean[] canCompleteRoot = { false };

        Set<TraceState> visited = new HashSet<>();
        Deque<TraceWork> stack = new ArrayDeque<>();
        for (String type : work.initialTypes()) {
            stack.push(new TraceWork(
                work.startDefinitionId(),
                work.startStepIndex(),
                type,
                work.callerStack(),
                work.callsiteChain()));
        }

        while (!stack.isEmpty()) {
            TraceWork current = stack.pop();
            TraceState state = new TraceState(
                current.definitionId(),
                current.stepIndex(),
                current.concreteType(),
                current.callsiteChain(),
                callerSignature(current.callerStack()));
            if (!visited.add(state)) {
                continue;
            }
            List<StepNode> steps = definitions.getOrDefault(current.definitionId(), List.of());
            if (current.stepIndex() >= steps.size()) {
                if (current.callerStack().isEmpty()) {
                    if (ROOT_ID.equals(current.definitionId())) {
                        canCompleteRoot[0] = true;
                    }
                } else {
                    CallerFrame frame = current.callerStack().getLast();
                    List<CallerFrame> remaining = current.callerStack().subList(0, current.callerStack().size() - 1);
                    stack.push(new TraceWork(
                        frame.definitionId(),
                        frame.callerStepIndex() + 1,
                        current.concreteType(),
                        List.copyOf(remaining),
                        frame.callsiteChain()));
                }
                continue;
            }

            StepNode step = steps.get(current.stepIndex());
            if (!step.accepts(current.concreteType())) {
                stack.push(new TraceWork(
                    current.definitionId(),
                    current.stepIndex() + 1,
                    current.concreteType(),
                    current.callerStack(),
                    current.callsiteChain()));
                continue;
            }

            reachableDefinitions.add(current.definitionId());

            if (step.targetDefinitionId().isPresent()) {
                String targetId = step.targetDefinitionId().orElseThrow();
                List<String> nextChain = step.recursive()
                    ? current.callsiteChain()
                    : append(current.callsiteChain(), step.stepName());
                List<CallerFrame> nextCallers = step.recursive()
                    ? current.callerStack()
                    : appendFrame(current.callerStack(),
                        new CallerFrame(current.definitionId(), current.stepIndex(), current.callsiteChain()));

                // Trace into target definition; also explore caller continuation if target can complete without suspending.
                Set<String> targetOutputs = traceNestedCall(
                    targetId,
                    current.concreteType(),
                    nextCallers,
                    nextChain,
                    definitions,
                    discoveredAwaits,
                    reachableDefinitions,
                    nextAwaits,
                    canCompleteRoot);
                if (!step.recursive()) {
                    for (String outType : targetOutputs) {
                        if (step.terminal() && current.callerStack().isEmpty() && ROOT_ID.equals(current.definitionId())) {
                            canCompleteRoot[0] = true;
                        } else {
                            stack.push(new TraceWork(
                                current.definitionId(),
                                current.stepIndex() + 1,
                                outType,
                                current.callerStack(),
                                current.callsiteChain()));
                        }
                    }
                } else {
                    for (String outType : targetOutputs) {
                        if (current.callerStack().isEmpty() && ROOT_ID.equals(current.definitionId())) {
                            canCompleteRoot[0] = true;
                        } else {
                            stack.push(new TraceWork(
                                current.definitionId(),
                                current.stepIndex() + 1,
                                outType,
                                current.callerStack(),
                                current.callsiteChain()));
                        }
                    }
                }
                continue;
            }

            if (step.hasAwait()) {
                String awaitId = awaitBoundaryId(current.definitionId(), current.callsiteChain(), step.stepName());
                nextAwaits.add(awaitId);
                discoveredAwaits.merge(awaitId,
                    new AwaitOrigin(
                        awaitId,
                        current.definitionId(),
                        current.stepIndex(),
                        step.awaitedResumedTypes(),
                        current.callerStack(),
                        current.callsiteChain()),
                    AwaitOrigin::merge);
                for (String passThroughType : step.nonAwaitedPassThroughTypes()) {
                    if (step.terminal() && current.callerStack().isEmpty() && ROOT_ID.equals(current.definitionId())) {
                        canCompleteRoot[0] = true;
                    } else {
                        stack.push(new TraceWork(
                            current.definitionId(),
                            current.stepIndex() + 1,
                            passThroughType,
                            current.callerStack(),
                            current.callsiteChain()));
                    }
                }
                continue;
            }

            for (String outType : step.producedTypes()) {
                if (step.terminal() && current.callerStack().isEmpty() && ROOT_ID.equals(current.definitionId())) {
                    canCompleteRoot[0] = true;
                } else {
                    stack.push(new TraceWork(
                        current.definitionId(),
                        current.stepIndex() + 1,
                        outType,
                        current.callerStack(),
                        current.callsiteChain()));
                }
            }
        }

        return new SegmentTrace(canCompleteRoot[0], reachableDefinitions, nextAwaits);
    }

    /**
     * Traces a local nested/self invocation to discover both reachable Await boundaries inside the
     * callee and the concrete output types that can return synchronously without suspending.
     */
    private Set<String> traceNestedCall(
        String targetDefinitionId,
        String inputType,
        List<CallerFrame> callerStack,
        List<String> callsiteChain,
        Map<String, List<StepNode>> definitions,
        Map<String, AwaitOrigin> discoveredAwaits,
        Set<String> reachableDefinitions,
        Set<String> nextAwaits,
        boolean[] canCompleteRoot
    ) {
        Set<String> returnedTypes = new LinkedHashSet<>();
        Set<TraceState> localVisited = new HashSet<>();
        Deque<LocalWork> stack = new ArrayDeque<>();
        stack.push(new LocalWork(targetDefinitionId, 0, inputType, callerStack, callsiteChain, 0));

        while (!stack.isEmpty()) {
            LocalWork current = stack.pop();
            TraceState state = new TraceState(
                current.definitionId(),
                current.stepIndex(),
                current.concreteType(),
                current.callsiteChain(),
                "depth=" + current.relativeDepth());
            if (!localVisited.add(state)) {
                continue;
            }
            List<StepNode> steps = definitions.getOrDefault(current.definitionId(), List.of());
            if (current.stepIndex() >= steps.size()) {
                if (current.relativeDepth() == 0) {
                    returnedTypes.add(current.concreteType());
                } else if (!current.callerStack().isEmpty()) {
                    CallerFrame frame = current.callerStack().getLast();
                    List<CallerFrame> remaining = current.callerStack().subList(0, current.callerStack().size() - 1);
                    stack.push(new LocalWork(
                        frame.definitionId(),
                        frame.callerStepIndex() + 1,
                        current.concreteType(),
                        List.copyOf(remaining),
                        frame.callsiteChain(),
                        current.relativeDepth() - 1));
                }
                continue;
            }

            StepNode step = steps.get(current.stepIndex());
            if (!step.accepts(current.concreteType())) {
                stack.push(new LocalWork(
                    current.definitionId(),
                    current.stepIndex() + 1,
                    current.concreteType(),
                    current.callerStack(),
                    current.callsiteChain(),
                    current.relativeDepth()));
                continue;
            }

            reachableDefinitions.add(current.definitionId());

            if (step.targetDefinitionId().isPresent()) {
                String nestedTarget = step.targetDefinitionId().orElseThrow();
                if (step.recursive()) {
                    // Recursive self-call re-enters the same definition with the same static chain.
                    stack.push(new LocalWork(
                        nestedTarget,
                        0,
                        current.concreteType(),
                        current.callerStack(),
                        current.callsiteChain(),
                        current.relativeDepth()));
                } else {
                    List<String> nextChain = append(current.callsiteChain(), step.stepName());
                    List<CallerFrame> nextCallers = appendFrame(current.callerStack(),
                        new CallerFrame(current.definitionId(), current.stepIndex(), current.callsiteChain()));
                    stack.push(new LocalWork(
                        nestedTarget,
                        0,
                        current.concreteType(),
                        nextCallers,
                        nextChain,
                        current.relativeDepth() + 1));
                }
                continue;
            }

            if (step.hasAwait()) {
                String awaitId = awaitBoundaryId(current.definitionId(), current.callsiteChain(), step.stepName());
                nextAwaits.add(awaitId);
                discoveredAwaits.merge(awaitId,
                    new AwaitOrigin(
                        awaitId,
                        current.definitionId(),
                        current.stepIndex(),
                        step.awaitedResumedTypes(),
                        current.callerStack(),
                        current.callsiteChain()),
                    AwaitOrigin::merge);
                for (String passThrough : step.nonAwaitedPassThroughTypes()) {
                    stack.push(new LocalWork(
                        current.definitionId(),
                        current.stepIndex() + 1,
                        passThrough,
                        current.callerStack(),
                        current.callsiteChain(),
                        current.relativeDepth()));
                }
                continue;
            }

            for (String outType : step.producedTypes()) {
                if (step.terminal() && current.relativeDepth() == 0 && ROOT_ID.equals(current.definitionId())
                    && current.callerStack().isEmpty()) {
                    canCompleteRoot[0] = true;
                }
                stack.push(new LocalWork(
                    current.definitionId(),
                    current.stepIndex() + 1,
                    outType,
                    current.callerStack(),
                    current.callsiteChain(),
                    current.relativeDepth()));
            }
        }
        return returnedTypes;
    }

    private static String awaitBoundaryId(String definitionId, List<String> callsiteChain, String stepName) {
        if (ROOT_ID.equals(definitionId) || callsiteChain.isEmpty()) {
            return definitionId + "/" + stepName;
        }
        return definitionId + "@" + String.join("->", callsiteChain) + "/" + stepName;
    }

    private static List<String> append(List<String> list, String value) {
        List<String> next = new ArrayList<>(list.size() + 1);
        next.addAll(list);
        next.add(value);
        return List.copyOf(next);
    }

    private static List<CallerFrame> appendFrame(List<CallerFrame> list, CallerFrame frame) {
        List<CallerFrame> next = new ArrayList<>(list.size() + 1);
        next.addAll(list);
        next.add(frame);
        return List.copyOf(next);
    }

    private static String callerSignature(List<CallerFrame> frames) {
        if (frames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CallerFrame frame : frames) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            sb.append(frame.definitionId()).append('#').append(frame.callerStepIndex());
        }
        return sb.toString();
    }

    private record StepNode(
        String definitionId,
        int index,
        String stepName,
        Optional<String> targetDefinitionId,
        boolean recursive,
        boolean hasAwait,
        Set<String> awaitedResumedTypes,
        Set<String> nonAwaitedPassThroughTypes,
        Set<String> acceptedTypes,
        Set<String> producedTypes,
        boolean terminal,
        boolean branchAware
    ) {
        boolean accepts(String concreteType) {
            return !branchAware || WILDCARD_TYPE.equals(concreteType) || acceptedTypes.contains(concreteType);
        }
    }

    private record CallerFrame(String definitionId, int callerStepIndex, List<String> callsiteChain) {
    }

    private record AwaitOrigin(
        String awaitId,
        String definitionId,
        int stepIndex,
        Set<String> resumedTypes,
        List<CallerFrame> callerStack,
        List<String> callsiteChain
    ) {
        AwaitOrigin merge(AwaitOrigin other) {
            Set<String> combined = new LinkedHashSet<>(resumedTypes);
            combined.addAll(other.resumedTypes);
            return new AwaitOrigin(awaitId, definitionId, stepIndex, Set.copyOf(combined), callerStack, callsiteChain);
        }
    }

    private record SegmentWork(
        String segmentId,
        String startKind,
        String resumeAwaitBoundaryId,
        String startDefinitionId,
        int startStepIndex,
        Set<String> initialTypes,
        List<CallerFrame> callerStack,
        List<String> callsiteChain
    ) {
    }

    private record TraceWork(
        String definitionId,
        int stepIndex,
        String concreteType,
        List<CallerFrame> callerStack,
        List<String> callsiteChain
    ) {
    }

    private record LocalWork(
        String definitionId,
        int stepIndex,
        String concreteType,
        List<CallerFrame> callerStack,
        List<String> callsiteChain,
        int relativeDepth
    ) {
    }

    private record TraceState(
        String definitionId,
        int stepIndex,
        String concreteType,
        List<String> callsiteChain,
        String callerKey
    ) {
    }

    private record SegmentTrace(
        boolean canCompleteRoot,
        Set<String> reachableDefinitions,
        Set<String> nextAwaitBoundaries
    ) {
    }
}
