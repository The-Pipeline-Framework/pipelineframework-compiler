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
        if (config.pipelines().values().stream().allMatch(definition -> definition.recur().isEmpty())) {
            return new Result(config, parsed);
        }
        if (!"LOCAL".equalsIgnoreCase(config.transport())) {
            throw new IllegalArgumentException("Definition recurrence currently requires pipeline.transport=LOCAL");
        }
        var definitions = new LinkedHashMap<String, PipelineTemplateDefinition>();
        var parsedDefinitions = new LinkedHashMap<>(parsed.localDefinitions());
        V3JavaTypeResolver javaTypes = new V3JavaTypeResolver(config);
        config.pipelines().forEach((id, definition) -> {
            if (definition.recur().isEmpty()) {
                definitions.put(id, definition);
                return;
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
            definitions.put(id, new PipelineTemplateDefinition(definition.inputContract(), definition.outputContract(),
                steps, definition.recur()));
            List<StepDefinition> authored = Optional.ofNullable(parsedDefinitions.get(id)).orElseThrow(() ->
                new IllegalArgumentException("Recurrent definition '" + id + "' has no parsed authored steps"));
            var resolved = new ArrayList<>(authored);
            resolved.add(StepDefinition.pipeline(CONTINUATION,
                javaTypes.resolve(definition.inputContract()).orElseThrow(),
                javaTypes.resolve(definition.outputContract()).orElseThrow(), StreamingShape.UNARY_UNARY,
                definition.recur(), false, id));
            parsedDefinitions.put(id, List.copyOf(resolved));
        });
        PipelineTemplateConfig lowered = new PipelineTemplateConfig(config.version(), config.appName(),
            config.basePackage(), config.transport(), config.platform(), config.messages(), config.unions(),
            config.sources(), config.publish(), config.steps(), config.aspects(), config.input(), config.output(),
            config.materialization(), config.inputContract(), config.outputContract(), config.typeModel(), definitions, config.httpPayloads());
        return new Result(lowered, new ParsedPipelineDefinitionCatalog(parsed.rootSteps(), parsedDefinitions));
    }
}
