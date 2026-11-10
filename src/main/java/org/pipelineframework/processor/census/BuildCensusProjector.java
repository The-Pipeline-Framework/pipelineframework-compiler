package org.pipelineframework.processor.census;

import java.util.*;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.ir.StepDefinition;
import org.pipelineframework.config.template.PipelineTemplateConfig;

/** Pure allowlisted projection of the original logical definition catalogue, never module-filtered IR. */
public final class BuildCensusProjector {
    /** Counts reachable logical definitions only for an error-free application orchestrator; skips incomplete nested graphs. */
    public Optional<BuildCensusSummary> project(PipelineCompilationContext ctx) {
        if (!ctx.isOrchestratorGenerated() || ctx.isPluginHost() || ctx.hasCompilationErrors()
                || ctx.getOriginalPipelineConfig().isEmpty()) return Optional.empty();
        var catalog = ctx.getParsedPipelineDefinitionCatalog();
        if (catalog.rootSteps().isEmpty()) return Optional.empty();
        Set<String> reachable = new HashSet<>();
        ctx.getResolvedPipelineDefinitionGraph().ifPresent(graph ->
            graph.definitions().keySet().forEach(ref -> reachable.add(ref.logicalId())));
        // A nested application is only observable with a complete linked graph.
        if (reachable.isEmpty() && catalog.rootSteps().stream().anyMatch(step -> step.pipelineReference().isPresent()))
            return Optional.empty();
        List<StepDefinition> steps = new ArrayList<>(catalog.rootSteps());
        catalog.localDefinitions().forEach((id, definitions) -> { if (reachable.contains(id)) steps.addAll(definitions); });
        long types = ctx.getPipelineTemplateConfig() instanceof PipelineTemplateConfig config
                && config.dialect() == org.pipelineframework.config.template.PipelineTemplateDialect.V3
                ? config.typeModel().definitions().size() : -1;
        long blocks = ctx.getImportedPipelineDefinitions().stream().filter(def -> reachable.contains(def.qualifiedId()))
                .map(def -> List.of(def.groupId(), def.artifactId())).distinct().count();
        Map<String, CountBand> sizes = Map.of("pipelines", CountBand.of(Math.max(1, reachable.size())),
                "steps", CountBand.of(steps.size()), "types", CountBand.of(types), "blocks", CountBand.of(blocks));
        Map<String, CountBand> features = new TreeMap<>();
        features.put("nested_invocation", count(steps, step -> step.pipelineReference().isPresent()));
        features.put("query", count(steps, step -> step.kind() == org.pipelineframework.processor.ir.StepKind.QUERY));
        features.put("command", count(steps, step -> step.kind() == org.pipelineframework.processor.ir.StepKind.COMMAND));
        features.put("await", count(steps, step -> step.deferredCompletion().isPresent()));
        List<org.pipelineframework.config.CardinalitySemantics> cardinalities = new ArrayList<>();
        ctx.getResolvedPipelineDefinitionGraph().ifPresent(graph -> {
            graph.definitions().values().forEach(definition -> definition.steps().forEach(step ->
                    step.directCardinality().ifPresent(cardinalities::add)));
            Map<org.pipelineframework.processor.composition.DefinitionLocalLocation,
                    org.pipelineframework.config.CardinalitySemantics> invocations = new HashMap<>();
            graph.invocationBindings().forEach(binding -> invocations.putIfAbsent(
                    binding.invocationLocation().definitionLocalLocation(), binding.cardinality()));
            cardinalities.addAll(invocations.values());
        });
        if (cardinalities.isEmpty()) {
            for (StepDefinition step : steps) {
                if (step.streamingShapeHint() != null) cardinalities.add(switch (step.streamingShapeHint()) {
                    case UNARY_UNARY -> org.pipelineframework.config.CardinalitySemantics.ONE_TO_ONE;
                    case UNARY_STREAMING -> org.pipelineframework.config.CardinalitySemantics.ONE_TO_MANY;
                    case STREAMING_UNARY -> org.pipelineframework.config.CardinalitySemantics.MANY_TO_ONE;
                    case STREAMING_STREAMING -> org.pipelineframework.config.CardinalitySemantics.MANY_TO_MANY;
                });
            }
        }
        features.put("one_to_many", cardinalities.size() != steps.size() ? CountBand.UNKNOWN : CountBand.of(
                cardinalities.stream().filter(value -> value == org.pipelineframework.config.CardinalitySemantics.ONE_TO_MANY).count()));
        features.put("many_to_one", cardinalities.size() != steps.size() ? CountBand.UNKNOWN : CountBand.of(
                cardinalities.stream().filter(value -> value == org.pipelineframework.config.CardinalitySemantics.MANY_TO_ONE).count()));
        return Optional.of(new BuildCensusSummary(sizes, features,
                BuildCensusSummary.Transport.valueOf(ctx.getTransportMode().name()),
                BuildCensusSummary.Platform.valueOf(ctx.getPlatformMode().name()),
                ctx.getRuntimeMapping() == null ? BuildCensusSummary.Layout.UNSPECIFIED
                        : BuildCensusSummary.Layout.valueOf(ctx.getRuntimeMapping().layout().name())));
    }
    private CountBand count(List<StepDefinition> steps, java.util.function.Predicate<StepDefinition> predicate) {
        return CountBand.of(steps.stream().filter(predicate).count());
    }
}
