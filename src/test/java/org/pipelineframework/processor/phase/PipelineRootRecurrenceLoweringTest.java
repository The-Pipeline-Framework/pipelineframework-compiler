package org.pipelineframework.processor.phase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.squareup.javapoet.ClassName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(strings = {"Run initialization", "Ad-vance", "AdvanceService"})
    void linksAndRendersRootRecurrenceWithoutAnAuthoredSecondConsumer(String callsiteName) throws Exception {
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
              Initial: { fields: [[request, string]] }
              Entry: { variants: { initial: Initial, turn: TurnState, result: Result } }
              Outcome: { variants: { again: TurnState, finished: Result } }
            pipelines:
              initialize:
                input: Initial
                output: TurnState
                steps:
                  - name: Initialize
                    service: com.example.Initialize
                    cardinality: ONE_TO_ONE
                    input: Initial
                    output: TurnState
                    java: { input: com.example.domain.Initial, output: com.example.domain.TurnState }
            steps:
              - name: Run initialization
                pipeline: initialize
                cardinality: ONE_TO_ONE
                input: Entry
                accepts: [Initial]
                output: TurnState
                java: { input: com.example.domain.Initial, output: com.example.domain.TurnState }
              - name: Advance
                service: com.example.Advance
                cardinality: ONE_TO_ONE
                input: Entry
                accepts: [TurnState]
                output: Outcome
            """.replace("Run initialization", callsiteName));
        var authored = new PipelineTemplateConfigLoader().load(file);
        new org.pipelineframework.processor.routing.AuthoredConsumerValidator().validate(authored);
        var parsed = new StepDefinitionParser().parseDefinitionCatalog(file);
        var lowered = new PipelineRecurrenceLowering().lower(authored, parsed);
        assertEquals(2, authored.steps().size());
        assertEquals(3, lowered.config().steps().size());
        assertEquals("$root", lowered.parsed().rootSteps().getLast().pipelineReference().orElseThrow());
        var env = org.mockito.Mockito.mock(javax.annotation.processing.ProcessingEnvironment.class);
        org.mockito.Mockito.when(env.getMessager()).thenReturn(
            org.mockito.Mockito.mock(javax.annotation.processing.Messager.class));
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
        var plan = new PipelineBranchRoutingPlanner().plan(ctx);
        if (callsiteName.equals("Ad-vance")) {
            assertTrue(plan.isEmpty(), "Normalized duplicate names must fail before replay generation");
            org.mockito.Mockito.verify(env.getMessager()).printMessage(
                org.mockito.ArgumentMatchers.eq(javax.tools.Diagnostic.Kind.ERROR),
                org.mockito.ArgumentMatchers.contains("require unique step names"));
            return;
        }
        ctx.setBranchingPlan(plan.orElseThrow());
        new PipelineCompositionLinkingPhase().link(ctx);
        var graph = ctx.getResolvedPipelineDefinitionGraph().orElseThrow();
        assertEquals(2, graph.invocationBindings().size());
        var recursive = graph.invocationBindings().stream().filter(binding -> binding.recursive()).findFirst().orElseThrow();
        var model = new PipelineStepModel.Builder()
            .serviceName("ProcessAdvanceService").generatedName("ProcessAdvanceService")
            .servicePackage("com.example").serviceClassName(ClassName.get("com.example", "Advance"))
            .inputMapping(TypeMapping.withoutMapper(ClassName.get("com.example.domain", "TurnState")))
            .outputMapping(TypeMapping.withoutMapper(ClassName.get("com.example.domain", "Outcome")))
            .streamingShape(StreamingShape.UNARY_UNARY).executionMode(ExecutionMode.DEFAULT)
            .deploymentRole(DeploymentRole.ORCHESTRATOR_CLIENT)
            .enabledTargets(Set.of(GenerationTarget.LOCAL_CLIENT_STEP)).build();
        ctx.setStepModels(List.of(model));
        var initialize = model.toBuilder().serviceName("ProcessInitializeService").generatedName("ProcessInitializeService")
            .serviceClassName(ClassName.get("com.example", "Initialize"))
            .inputMapping(TypeMapping.withoutMapper(ClassName.get("com.example.domain", "Initial")))
            .outputMapping(TypeMapping.withoutMapper(ClassName.get("com.example.domain", "TurnState"))).build();
        ctx.setLocalDefinitionStepModels(java.util.Map.of("initialize", List.of(initialize)));
        var metadata = new org.pipelineframework.processor.util.PipelineTelemetryMetadataGenerator(env);
        if (callsiteName.equals("AdvanceService")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> metadata.writeTelemetryMetadata(ctx));
            assertTrue(failure.getMessage().contains("AdvanceService"));
            assertTrue(failure.getMessage().contains("Advance"));
            return;
        }
        metadata.writeTelemetryMetadata(ctx);
        var topology = com.google.gson.JsonParser.parseString(
            resources.get("META-INF/pipeline/replay-topology.json").toString()).getAsJsonObject();
        var recursiveClass = org.pipelineframework.processor.composition.LocalPipelineInvocationClassName
            .canonicalName(authored.basePackage(), recursive.invocationLocation());
        var transitions = topology.getAsJsonArray("transitions").asList();
        assertTrue(transitions.stream().anyMatch(element -> element.getAsJsonObject()
            .get("fromRuntimeStepClass").getAsString().equals(recursiveClass)), topology.toString());
        assertTrue(transitions.stream().anyMatch(element -> element.getAsJsonObject()
            .get("toRuntimeStepClass").getAsString().equals(recursiveClass)), topology.toString());
        Path generated = tempDir.resolve("generated");
        new LocalPipelineInvocationRenderer().render(ctx, generated);
        try (var sources = Files.walk(generated)) {
            var java = sources.filter(path -> path.toString().endsWith(".java")).toList();
            assertEquals(2, java.size());
            var source = new StringBuilder();
            for (var path : java) source.append(Files.readString(path));
            assertTrue(source.toString().contains("recursiveOneToOne"), source.toString());
            assertTrue(source.toString().contains("ProcessAdvanceLocalClientStep"), source.toString());
            assertTrue(source.toString().contains("Provider<"), source.toString());
        }
    }
}
