package org.pipelineframework.processor.phase;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;

import com.squareup.javapoet.ClassName;
import org.pipelineframework.annotation.PipelineOrchestrator;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateStep;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.OrchestratorBinding;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.StepDefinition;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

/**
 * Builds orchestrator-specific bindings.
 */
class OrchestratorBindingBuilder {

    /**
     * Builds an orchestrator binding from the template config and orchestrator elements.
     *
     * @param config the pipeline template config
     * @param orchestratorElements the set of orchestrator elements
     * @return an orchestrator binding or null if not applicable
     */
    static OrchestratorBinding buildOrchestratorBinding(
            PipelineTemplateConfig config,
            Set<? extends Element> orchestratorElements,
            String resolvedTransport,
            List<StepDefinition> stepDefinitions) {
        return buildOrchestratorBinding(config, orchestratorElements, resolvedTransport, stepDefinitions, Optional.empty());
    }

    static OrchestratorBinding buildOrchestratorBinding(
            PipelineTemplateConfig config,
            Set<? extends Element> orchestratorElements,
            String resolvedTransport,
            List<StepDefinition> stepDefinitions,
            Optional<org.pipelineframework.processor.composition.ResolvedPipelineDefinitionGraph> resolvedGraph) {
        if (config == null) {
            return null;
        }
        Objects.requireNonNull(stepDefinitions, "stepDefinitions must not be null");
        List<PipelineTemplateStep> steps = config.steps();
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        String basePackage = config.basePackage();
        if (basePackage == null || basePackage.isBlank()) {
            return null;
        }
        PipelineTemplateStep first = steps.getFirst();
        PipelineTemplateStep last = steps.getLast();
        if (first == null || last == null) {
            return null;
        }
        String inputType = config.inputContract() != null && !config.inputContract().isBlank()
            ? config.inputContract()
            : first.inputTypeName();
        String outputType = config.outputContract() != null && !config.outputContract().isBlank()
            ? config.outputContract()
            : last.outputTypeName();
        if (inputType == null || inputType.isBlank() || outputType == null || outputType.isBlank()) {
            return null;
        }

        boolean inputStreaming;
        boolean outputStreaming;
        if (resolvedGraph != null && resolvedGraph.isPresent()) {
            var rootCardinality = resolvedGraph.orElseThrow().rootCardinality();
            inputStreaming = StreamingShapeResolver.isStreamingInputCardinality(rootCardinality.name());
            outputStreaming = rootCardinality == org.pipelineframework.config.CardinalitySemantics.ONE_TO_MANY
                || rootCardinality == org.pipelineframework.config.CardinalitySemantics.MANY_TO_MANY;
        } else {
            inputStreaming = StreamingShapeResolver.isStreamingInputCardinality(first.cardinality());
            outputStreaming = inputStreaming;
            for (PipelineTemplateStep step : steps) {
                outputStreaming = StreamingShapeResolver.applyCardinalityToStreaming(step.cardinality(), outputStreaming);
            }
        }

        String firstServiceNameFormatted = NamingPolicy.formatForClassName(NamingPolicy.stripProcessPrefix(first.name()));
        String firstServiceName = "Process" + firstServiceNameFormatted + "Service";
        StreamingShape firstStreamingShape = StreamingShapeResolver.streamingShape(first.cardinality());
        TypeMapping inputMapping = rootMapping(config, stepDefinitions, first, true, inputType);
        TypeMapping outputMapping = rootMapping(config, stepDefinitions, last, false, outputType);

        PipelineStepModel model = new PipelineStepModel(
            "OrchestratorService",
            "OrchestratorService",
            basePackage + ".orchestrator.service",
            ClassName.get(basePackage + ".orchestrator.service", "OrchestratorService"),
            inputMapping,
            outputMapping,
            StreamingShapeResolver.streamingShape(inputStreaming, outputStreaming),
            Set.of(GenerationTarget.GRPC_SERVICE),
            org.pipelineframework.processor.ir.ExecutionMode.DEFAULT,
            org.pipelineframework.processor.ir.DeploymentRole.ORCHESTRATOR_CLIENT,
            false,
            null
        );

        PipelineOrchestrator orchestratorAnnotation = resolveOrchestratorAnnotation(orchestratorElements);
        String cliName = orchestratorAnnotation == null ? null : NamingPolicy.emptyToNull(orchestratorAnnotation.name());
        String cliDescription = orchestratorAnnotation == null ? null : NamingPolicy.emptyToNull(orchestratorAnnotation.description());
        String cliVersion = orchestratorAnnotation == null ? null : NamingPolicy.emptyToNull(orchestratorAnnotation.version());

        String transport = (resolvedTransport == null || resolvedTransport.isBlank())
            ? config.transport()
            : resolvedTransport;

        return new OrchestratorBinding(
            model,
            basePackage,
            transport,
            inputType,
            outputType,
            inputStreaming,
            outputStreaming,
            firstServiceName,
            firstStreamingShape,
            cliName,
            cliDescription,
            cliVersion
        );
    }

    private static TypeMapping rootMapping(
            PipelineTemplateConfig config,
            List<StepDefinition> stepDefinitions,
            PipelineTemplateStep edgeStep,
            boolean input,
            String canonicalTypeName) {
        String stepContract = edgeStep == null
            ? null
            : (input ? edgeStep.inputTypeName() : edgeStep.outputTypeName());
        boolean explicitContractDiffers = config.dialect() == org.pipelineframework.config.template.PipelineTemplateDialect.V3
            && canonicalTypeName != null
            && !canonicalTypeName.isBlank()
            && stepContract != null
            && !canonicalTypeName.equals(stepContract);
        Optional<ClassName> javaType = Optional.empty();
        if (!explicitContractDiffers && !stepDefinitions.isEmpty()) {
            javaType = Optional.ofNullable(input
                ? stepDefinitions.getFirst().inputType()
                : stepDefinitions.getLast().outputType());
        }
        if (javaType.isEmpty()
            && config.dialect() == org.pipelineframework.config.template.PipelineTemplateDialect.V3) {
            javaType = new org.pipelineframework.processor.routing.V3JavaTypeResolver(config)
                .resolve(canonicalTypeName);
        }
        if (javaType.isEmpty() && !stepDefinitions.isEmpty()) {
            javaType = Optional.ofNullable(input
                ? stepDefinitions.getFirst().inputType()
                : stepDefinitions.getLast().outputType());
        }
        return javaType
            .map(type -> TypeMapping.canonical(type, canonicalTypeName))
            .orElseGet(TypeMapping::unresolved);
    }

    /**
     * Resolves the orchestrator annotation from the provided elements.
     *
     * @param orchestratorElements the set of orchestrator elements
     * @return the orchestrator annotation or null
     */
    static PipelineOrchestrator resolveOrchestratorAnnotation(Set<? extends Element> orchestratorElements) {
        if (orchestratorElements == null || orchestratorElements.isEmpty()) {
            return null;
        }
        for (Element element : orchestratorElements) {
            PipelineOrchestrator annotation = element.getAnnotation(PipelineOrchestrator.class);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }
}
