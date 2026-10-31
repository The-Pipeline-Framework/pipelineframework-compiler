package org.pipelineframework.processor.phase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.processor.Jsr269SourceInventory;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.parser.StepDefinitionParser;
import org.pipelineframework.processor.routing.PipelineBranchRoutingPlanner;

import static org.junit.jupiter.api.Assertions.*;

class PipelineRecurrenceLoweringTest {
    @TempDir
    Path tempDir;

    @Test
    void lowersRecurrentAgentToTheExistingBoundedInvocationAndCheckedUnionReturn() throws Exception {
        Path file = tempDir.resolve("agent.yaml");
        Files.writeString(file, """
            version: 3
            appName: Recurrent agent
            basePackage: com.example
            transport: LOCAL
            contract: { input: TurnState, output: AgentEnd }
            types:
              TurnState: { fields: [[turn, int32]] }
              Review: { fields: [[prompt, string]] }
              Result: { fields: [[message, string]] }
              TurnOutcome: { variants: { again: TurnState, review: Review, finished: Result } }
              AgentEnd: { variants: { review: Review, finished: Result } }
            pipelines:
              agent:
                input: TurnState
                output: AgentEnd
                recur: [TurnState]
                steps:
                  - name: Decide next turn
                    service: com.example.Advance
                    cardinality: ONE_TO_ONE
                    input: TurnState
                    output: TurnOutcome
                    java: { input: com.example.domain.TurnState, output: com.example.domain.TurnOutcome }
            steps:
              - name: Run agent
                pipeline: agent
                cardinality: ONE_TO_ONE
                input: TurnState
                output: AgentEnd
                java: { input: com.example.domain.TurnState, output: com.example.domain.AgentEnd }
            """);
        var authored = new PipelineTemplateConfigLoader().load(file);
        var parsed = new StepDefinitionParser().parseDefinitionCatalog(file);
        var lowered = new PipelineRecurrenceLowering().lower(authored, parsed);
        assertEquals(1, authored.pipelines().get("agent").steps().size());
        assertEquals(2, lowered.config().pipelines().get("agent").steps().size());
        assertEquals(List.of("TurnState"), lowered.parsed().localDefinitions().get("agent").getLast().accepts());
        assertEquals("agent", lowered.parsed().localDefinitions().get("agent").getLast()
            .pipelineReference().orElseThrow());
        var ctx = new PipelineCompilationContext(null, Jsr269SourceInventory.empty());
        ctx.setTransportMode(PipelineTransport.LOCAL);
        ctx.setPipelineTemplateConfig(lowered.config());
        ctx.setParsedPipelineDefinitionCatalog(lowered.parsed());
        ctx.setStepDefinitions(lowered.parsed().rootSteps());
        ctx.setBranchingPlan(new PipelineBranchRoutingPlanner().plan(ctx).orElseThrow());
        new PipelineCompositionLinkingPhase().link(ctx);
        var graph = ctx.getResolvedPipelineDefinitionGraph().orElseThrow();
        assertTrue(graph.invocationBindings().stream().anyMatch(binding -> binding.recursive()
            && binding.invocationLocation().definitionLocalLocation().localStepId().equals("$recur")));
        var plan = ctx.getLocalDefinitionBranchingPlans().values().iterator().next();
        assertEquals(-1, plan.terminalStepIndex());
        assertEquals(List.of("TurnState"), plan.steps().getLast().acceptedContractTypes());
        var input = com.squareup.javapoet.ClassName.get("com.example.domain", "TurnState");
        var outcome = com.squareup.javapoet.ClassName.get("com.example.domain", "TurnOutcome");
        var operation = new org.pipelineframework.processor.ir.PipelineStepModel.Builder()
            .serviceName("ProcessDecideNextTurnService").generatedName("ProcessDecideNextTurnService")
            .servicePackage("com.example")
            .serviceClassName(com.squareup.javapoet.ClassName.get("com.example", "Advance"))
            .inputMapping(org.pipelineframework.processor.ir.TypeMapping.withoutMapper(input))
            .outputMapping(org.pipelineframework.processor.ir.TypeMapping.withoutMapper(outcome))
            .streamingShape(org.pipelineframework.processor.ir.StreamingShape.UNARY_UNARY)
            .executionMode(org.pipelineframework.processor.ir.ExecutionMode.DEFAULT)
            .deploymentRole(org.pipelineframework.processor.ir.DeploymentRole.ORCHESTRATOR_CLIENT)
            .enabledTargets(java.util.Set.of(org.pipelineframework.processor.ir.GenerationTarget.LOCAL_CLIENT_STEP))
            .definition(new org.pipelineframework.processor.composition.PipelineReference("agent")).build();
        ctx.setLocalDefinitionStepModels(java.util.Map.of("agent", List.of(operation)));
        ctx.setStepModels(List.of(operation));
        Path generated = tempDir.resolve("generated");
        new org.pipelineframework.processor.renderer.LocalPipelineInvocationRenderer().render(ctx, generated);
        try (var sources = Files.walk(generated)) {
            List<String> code = sources.filter(path -> path.toString().endsWith(".java"))
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (java.io.IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                }).toList();
            assertTrue(code.stream().anyMatch(source -> source.contains("recursiveOneToOne")
                && source.contains("$recur") && source.contains("Provider<")),
                "The compiler must use the bounded recursive adapter and lazy self-reference");
        }
        assertThrows(IllegalArgumentException.class, () -> new PipelineRecurrenceLowering()
            .lower(lowered.config(), lowered.parsed()), "Lowering twice must not append another continuation");
    }
}
