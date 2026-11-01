package org.pipelineframework.processor.phase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateDefinition;
import org.pipelineframework.config.template.PipelineTemplateStep;
import org.pipelineframework.processor.ir.StepDefinition;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.parser.ParsedPipelineDefinitionCatalog;
import org.pipelineframework.processor.routing.V3JavaTypeResolver;

/** Lowers a definition-owned continuing outcome into the existing bounded invocation path. */
final class PipelineRecurrenceLowering {
    static final String CONTINUATION = "$recur";

    record Result(PipelineTemplateConfig config, ParsedPipelineDefinitionCatalog parsed) { }

    Result lower(PipelineTemplateConfig config, ParsedPipelineDefinitionCatalog parsed) {
        if (config.recur().isEmpty() && config.pipelines().values().stream().allMatch(definition -> definition.recur().isEmpty())) {
            return new Result(config, parsed);
        }
        if (!"LOCAL".equalsIgnoreCase(config.transport())) {
            throw new IllegalArgumentException("Definition recurrence currently requires pipeline.transport=LOCAL");
        }
        var definitions = new LinkedHashMap<String, PipelineTemplateDefinition>();
        var parsedDefinitions = new LinkedHashMap<>(parsed.localDefinitions());
        V3JavaTypeResolver javaTypes = new V3JavaTypeResolver(config);
        config.pipelines().forEach((id, definition) -> {
            var lowered = lowerDefinition(id, definition,
                Optional.ofNullable(parsedDefinitions.get(id)).orElseThrow(() ->
                    new IllegalArgumentException("Pipeline definition '" + id + "' has no parsed authored steps")), javaTypes);
            definitions.put(id, lowered.definition());
            parsedDefinitions.put(id, lowered.steps());
        });
        var rootSteps = config.steps();
        var parsedRoot = parsed.rootSteps();
        if (!config.recur().isEmpty()) {
            var root = lowerDefinition("$root", new PipelineTemplateDefinition(config.inputContract(),
                config.outputContract(), rootSteps, config.recur()), parsedRoot, javaTypes);
            rootSteps = root.definition().steps();
            parsedRoot = root.steps();
        }
        PipelineTemplateConfig lowered = new PipelineTemplateConfig(config.version(), config.appName(),
            config.basePackage(), config.transport(), config.platform(), config.messages(), config.unions(),
            config.sources(), config.publish(), rootSteps, config.aspects(), config.input(), config.output(),
            config.materialization(), config.inputContract(), config.outputContract(), config.typeModel(), definitions, config.httpPayloads(), config.recur());
        return new Result(lowered, new ParsedPipelineDefinitionCatalog(parsedRoot, parsedDefinitions));
    }
    private record LoweredDefinition(PipelineTemplateDefinition definition, List<StepDefinition> steps) { }

    private LoweredDefinition lowerDefinition(String id, PipelineTemplateDefinition definition,
        List<StepDefinition> authored, V3JavaTypeResolver javaTypes) {
        if (definition.recur().isEmpty()) {
            return new LoweredDefinition(definition, authored);
        }
        if (definition.steps().stream().anyMatch(step -> step.terminal()
            || CONTINUATION.equals(step.name()) || step.pipelineReference().filter(id::equals).isPresent())) {
            throw new IllegalArgumentException("Definition '" + id
                + "' recur cannot be combined with an authored self-call, terminal merge, or reserved $recur step");
        }
        if (definition.steps().stream().anyMatch(step -> !"ONE_TO_ONE".equalsIgnoreCase(step.cardinality()))) {
            throw new IllegalArgumentException("Definition '" + id + "' recurrence currently requires ONE_TO_ONE steps");
        }
        PipelineTemplateStep defaults = new PipelineTemplateStep(CONTINUATION, "ONE_TO_ONE",
            definition.inputContract(), List.of(), definition.outputContract(), List.of());
        PipelineTemplateStep continuation = new PipelineTemplateStep(defaults.name(), defaults.cardinality(),
            defaults.inputTypeName(), defaults.inputFields(), defaults.inboundMapper(), defaults.outputTypeName(),
            defaults.outputFields(), defaults.outboundMapper(), defaults.execution(), definition.recur(), false,
            Optional.of(id));
        var steps = new ArrayList<>(definition.steps());
        steps.add(continuation);
        var parsed = new ArrayList<>(authored);
        parsed.add(StepDefinition.pipeline(CONTINUATION,
            javaTypes.resolve(definition.inputContract()).orElseThrow(),
            javaTypes.resolve(definition.outputContract()).orElseThrow(), StreamingShape.UNARY_UNARY,
            definition.recur(), false, id));
        return new LoweredDefinition(new PipelineTemplateDefinition(definition.inputContract(),
            definition.outputContract(), steps, definition.recur()), List.copyOf(parsed));
    }

}
