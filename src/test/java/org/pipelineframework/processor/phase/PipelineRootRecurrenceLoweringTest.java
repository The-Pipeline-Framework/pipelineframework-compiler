package org.pipelineframework.processor.phase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.processor.Jsr269SourceInventory;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.ir.*;
import org.pipelineframework.processor.parser.StepDefinitionParser;
import org.pipelineframework.processor.renderer.LocalPipelineInvocationRenderer;
import org.pipelineframework.processor.routing.PipelineBranchRoutingPlanner;

import static org.junit.jupiter.api.Assertions.*;

class PipelineRootRecurrenceLoweringTest {
    @TempDir Path tempDir;

    @Test
    void linksAndRendersRootRecurrenceWithoutAnAuthoredSecondConsumer() throws Exception {
        Path file = tempDir.resolve("root.yaml");
        Files.writeString(file, """
            version: 3
            appName: Root recurrence
            basePackage: com.example
            transport: LOCAL
            contract: { input: Entry, output: Result }
            recur: [TurnState]
            types:
              TurnState: { fields: [[turn, int32]] }
              Result: { fields: [[message, string]] }
              Entry: { variants: { turn: TurnState, result: Result } }
              Outcome: { variants: { again: TurnState, finished: Result } }
            steps:
              - name: Advance
                service: com.example.Advance
                cardinality: ONE_TO_ONE
                input: Entry
                accepts: [TurnState]
                output: Outcome
            """);
        var authored = new PipelineTemplateConfigLoader().load(file);
        var parsed = new StepDefinitionParser().parseDefinitionCatalog(file);
        var lowered = new PipelineRecurrenceLowering().lower(authored, parsed);
        assertEquals(1, authored.steps().size());
        assertEquals(2, lowered.config().steps().size());
        assertEquals("$root", lowered.parsed().rootSteps().getLast().pipelineReference().orElseThrow());
        var env = org.mockito.Mockito.mock(javax.annotation.processing.ProcessingEnvironment.class);
        org.mockito.Mockito.when(env.getOptions()).thenReturn(java.util.Map.of("pipeline.config", file.toString()));
        var filer = org.mockito.Mockito.mock(javax.annotation.processing.Filer.class);
        org.mockito.Mockito.when(env.getFiler()).thenReturn(filer);
        var resources = new java.util.HashMap<String, java.io.StringWriter>();
        org.mockito.Mockito.when(filer.createResource(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(""), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(javax.lang.model.element.Element[].class)))
            .thenAnswer(invocation -> {
                String name = invocation.getArgument(2).toString();
                var writer = new java.io.StringWriter();
                resources.put(name, writer);
                return new javax.tools.SimpleJavaFileObject(java.net.URI.create("memory:///" + name),
                    javax.tools.JavaFileObject.Kind.OTHER) {
                    @Override public java.io.Writer openWriter() { return writer; }
                };
            });
        var ctx = new PipelineCompilationContext(env, Jsr269SourceInventory.empty());
        ctx.setModuleDir(tempDir);
        ctx.setTransportMode(PipelineTransport.LOCAL);
        ctx.setPipelineTemplateConfig(lowered.config());
        ctx.setParsedPipelineDefinitionCatalog(lowered.parsed());
        ctx.setStepDefinitions(lowered.parsed().rootSteps());
        ctx.setBranchingPlan(new PipelineBranchRoutingPlanner().plan(ctx).orElseThrow());
        new PipelineCompositionLinkingPhase().link(ctx);
        var graph = ctx.getResolvedPipelineDefinitionGraph().orElseThrow();
        assertEquals(1, graph.invocationBindings().size());
        assertTrue(graph.invocationBindings().getFirst().recursive());
        var model = new PipelineStepModel.Builder()
            .serviceName("ProcessAdvanceService").generatedName("ProcessAdvanceService")
            .servicePackage("com.example").serviceClassName(ClassName.get("com.example", "Advance"))
            .inputMapping(TypeMapping.withoutMapper(ClassName.get("com.example.domain", "TurnState")))
            .outputMapping(TypeMapping.withoutMapper(ClassName.get("com.example.domain", "Outcome")))
            .streamingShape(StreamingShape.UNARY_UNARY).executionMode(ExecutionMode.DEFAULT)
            .deploymentRole(DeploymentRole.ORCHESTRATOR_CLIENT)
            .enabledTargets(Set.of(GenerationTarget.LOCAL_CLIENT_STEP)).build();
        ctx.setStepModels(List.of(model));
        new org.pipelineframework.processor.util.PipelineTelemetryMetadataGenerator(env).writeTelemetryMetadata(ctx);
        var topology = com.google.gson.JsonParser.parseString(
            resources.get("META-INF/pipeline/replay-topology.json").toString()).getAsJsonObject();
        var recursiveClass = org.pipelineframework.processor.composition.LocalPipelineInvocationClassName
            .canonicalName(authored.basePackage(), graph.invocationBindings().getFirst().invocationLocation());
        var transitions = topology.getAsJsonArray("transitions").asList();
        assertTrue(transitions.stream().anyMatch(element -> element.getAsJsonObject()
            .get("fromRuntimeStepClass").getAsString().equals(recursiveClass)), topology.toString());
        assertTrue(transitions.stream().anyMatch(element -> element.getAsJsonObject()
            .get("toRuntimeStepClass").getAsString().equals(recursiveClass)), topology.toString());
        Path generated = tempDir.resolve("generated");
        new LocalPipelineInvocationRenderer().render(ctx, generated);
        try (var sources = Files.walk(generated)) {
            var java = sources.filter(path -> path.toString().endsWith(".java")).toList();
            assertEquals(1, java.size());
            var source = Files.readString(java.getFirst());
            assertTrue(source.contains("recursiveOneToOne"), source);
            assertTrue(source.contains("ProcessAdvanceLocalClientStep"), source);
            assertTrue(source.contains("Provider<"), source);
        }
    }
}
