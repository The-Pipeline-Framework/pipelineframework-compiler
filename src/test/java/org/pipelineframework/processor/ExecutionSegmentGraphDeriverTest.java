package org.pipelineframework.processor;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.orchestrator.release.PipelinePlacementDescriptor;
import org.pipelineframework.orchestrator.release.PipelinePlacementDescriptor.ExecutionSegment;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.PipelineCompilationPhase;
import org.pipelineframework.processor.PipelineCompiler;
import org.pipelineframework.processor.PipelineStepProcessor;
import org.pipelineframework.processor.phase.ModelExtractionPhase;
import org.pipelineframework.processor.phase.PipelineBranchPlanningPhase;
import org.pipelineframework.processor.phase.PipelineDiscoveryPhase;
import org.pipelineframework.processor.phase.PipelineRuntimeMappingPhase;
import org.pipelineframework.processor.phase.PipelineSemanticAnalysisPhase;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionSegmentGraphDeriverTest {

    @TempDir Path directory;

    @Test
    void sequentialAwaitsDeriveThreeChainedSegmentsInOnePhysicalModule() throws Exception {
        Path moduleDir = Files.createDirectories(directory.resolve("seq-app"));
        Path yaml = Files.writeString(moduleDir.resolve("pipeline.yaml"), """
            version: 3
            appName: Sequential Awaits
            basePackage: segproof
            transport: REST
            platform: FUNCTION
            contract: { input: Req, output: Done }
            types:
              Req: { java: segproof.Types.Req, fields: [[id, string]] }
              Mid: { java: segproof.Types.Mid, fields: [[id, string]] }
              PendingOne: { java: segproof.Types.PendingOne, fields: [[id, string]] }
              PendingTwo: { java: segproof.Types.PendingTwo, fields: [[id, string]] }
              Done: { java: segproof.Types.Done, fields: [[id, string]] }
            steps:
              - name: First
                service: segproof.Types.FirstSvc
                cardinality: ONE_TO_ONE
                input: Req
                output: Mid
                java: { input: segproof.Types.Req, output: segproof.Types.Mid }
                await:
                  operationOutput: { type: PendingOne, java: segproof.Types.PendingOne }
                  timeout: PT5M
                  correlation: { strategy: interactionId }
                  transport: { type: interaction-api }
              - name: Second
                service: segproof.Types.SecondSvc
                cardinality: ONE_TO_ONE
                input: Mid
                output: Done
                java: { input: segproof.Types.Mid, output: segproof.Types.Done }
                await:
                  operationOutput: { type: PendingTwo, java: segproof.Types.PendingTwo }
                  timeout: PT5M
                  correlation: { strategy: interactionId }
                  transport: { type: interaction-api }
            """);
        writeColocatedRuntimeMapping(moduleDir, "seq-app");
        String source = """
            package segproof;
            public class Types {
              public record Req(String id) {}
              public record Mid(String id) {}
              public record PendingOne(String id) {}
              public record PendingTwo(String id) {}
              public record Done(String id) {}
              public static class FirstSvc implements org.pipelineframework.service.ReactiveService<Req, PendingOne> {
                public io.smallrye.mutiny.Uni<PendingOne> process(Req in) { return io.smallrye.mutiny.Uni.createFrom().item(new PendingOne(in.id())); }
              }
              public static class SecondSvc implements org.pipelineframework.service.ReactiveService<Mid, PendingTwo> {
                public io.smallrye.mutiny.Uni<PendingTwo> process(Mid in) { return io.smallrye.mutiny.Uni.createFrom().item(new PendingTwo(in.id())); }
              }
            }
            """;

        PipelinePlacementDescriptor placement = derive(moduleDir, yaml, source, "seq-app");
        assertTrue(placement.present());
        Map<String, ExecutionSegment> byId = placement.executionSegments().stream()
            .collect(Collectors.toMap(ExecutionSegment::segmentId, Function.identity()));
        assertEquals(3, byId.size());
        assertFalse(byId.get("entry").canCompleteRoot());
        assertEquals(List.of("$root/First"), byId.get("entry").nextAwaitBoundaries());
        assertFalse(byId.get("resume:$root/First").canCompleteRoot());
        assertEquals(List.of("$root/Second"), byId.get("resume:$root/First").nextAwaitBoundaries());
        assertTrue(byId.get("resume:$root/Second").canCompleteRoot());
        assertEquals(List.of(), byId.get("resume:$root/Second").nextAwaitBoundaries());
        assertEquals(java.util.Set.of("seq-app"), placement.deployedModules());
    }

    @Test
    void mutuallyExclusiveBranchesAndSelfLoopDeriveRealGraphWithoutPhantomChaining() throws Exception {
        Path moduleDir = Files.createDirectories(directory.resolve("branch-app"));
        Path yaml = Files.writeString(moduleDir.resolve("pipeline.yaml"), """
            version: 3
            appName: Branch and Recursive Await
            basePackage: segproof
            transport: REST
            platform: FUNCTION
            contract: { input: Start, output: Done }
            types:
              Start: { java: segproof.Types.Start, fields: [[id, string]] }
              FastPath: { java: segproof.Types.FastPath, fields: [[id, string]] }
              BranchA: { java: segproof.Types.BranchA, fields: [[id, string]] }
              BranchB: { java: segproof.Types.BranchB, fields: [[id, string]] }
              Choice:
                java: segproof.Types.Choice
                variants: { fast: FastPath, branchA: BranchA, branchB: BranchB }
              PendingA: { java: segproof.Types.PendingA, fields: [[id, string]] }
              PendingB: { java: segproof.Types.PendingB, fields: [[id, string]] }
              AnswerA: { java: segproof.Types.AnswerA, fields: [[id, string]] }
              AnswerB: { java: segproof.Types.AnswerB, fields: [[id, string]] }
              LoopChoice:
                java: segproof.Types.LoopChoice
                variants: { again: Start, done: Done }
              Done: { java: segproof.Types.Done, fields: [[id, string]] }
            pipelines:
              nested-wait:
                input: BranchB
                output: LoopChoice
                steps:
                  - name: Nested Await Step
                    service: segproof.Types.NestedSvc
                    cardinality: ONE_TO_ONE
                    input: BranchB
                    output: AnswerB
                    java: { input: segproof.Types.BranchB, output: segproof.Types.AnswerB }
                    await:
                      operationOutput: { type: PendingB, java: segproof.Types.PendingB }
                      timeout: PT5M
                      correlation: { strategy: interactionId }
                      transport: { type: interaction-api }
                  - name: Decide Loop
                    service: segproof.Types.DecideLoopSvc
                    cardinality: ONE_TO_ONE
                    input: AnswerB
                    output: LoopChoice
                    java: { input: segproof.Types.AnswerB, output: segproof.Types.LoopChoice }
            steps:
              - name: Route
                service: segproof.Types.RouteSvc
                cardinality: ONE_TO_ONE
                input: Start
                output: Choice
                java: { input: segproof.Types.Start, output: segproof.Types.Choice }
              - name: Fast Finish
                service: segproof.Types.FastSvc
                cardinality: ONE_TO_ONE
                input: Choice
                accepts: [FastPath]
                output: Done
                java: { input: segproof.Types.FastPath, output: segproof.Types.Done }
              - name: Root Await Step
                service: segproof.Types.RootWaitSvc
                cardinality: ONE_TO_ONE
                input: Choice
                accepts: [BranchA]
                output: AnswerA
                java: { input: segproof.Types.BranchA, output: segproof.Types.AnswerA }
                await:
                  operationOutput: { type: PendingA, java: segproof.Types.PendingA }
                  timeout: PT5M
                  correlation: { strategy: interactionId }
                  transport: { type: interaction-api }
              - name: Finish A
                service: segproof.Types.FinishASvc
                cardinality: ONE_TO_ONE
                input: AnswerA
                output: Done
                java: { input: segproof.Types.AnswerA, output: segproof.Types.Done }
              - name: Call Nested Wait
                pipeline: nested-wait
                cardinality: ONE_TO_ONE
                input: Choice
                accepts: [BranchB]
                output: LoopChoice
                java: { input: segproof.Types.BranchB, output: segproof.Types.LoopChoice }
              - name: Continue Self
                pipeline: self
                cardinality: ONE_TO_ONE
                input: LoopChoice
                accepts: [Start]
                output: Done
                java: { input: segproof.Types.Start, output: segproof.Types.Done }
            """);
        writeColocatedRuntimeMapping(moduleDir, "branch-app");
        String source = """
            package segproof;
            public class Types {
              public record Start(String id) {}
              public record FastPath(String id) {}
              public record BranchA(String id) {}
              public record BranchB(String id) {}
              public sealed interface Choice permits Choice.Fast, Choice.A, Choice.B {
                record Fast(FastPath value) implements Choice {}
                record A(BranchA value) implements Choice {}
                record B(BranchB value) implements Choice {}
              }
              public record PendingA(String id) {}
              public record PendingB(String id) {}
              public record AnswerA(String id) {}
              public record AnswerB(String id) {}
              public sealed interface LoopChoice permits LoopChoice.Again, LoopChoice.Finished {
                record Again(Start value) implements LoopChoice {}
                record Finished(Done value) implements LoopChoice {}
              }
              public record Done(String id) {}
              public static class RouteSvc implements org.pipelineframework.service.ReactiveService<Start, Choice> {
                public io.smallrye.mutiny.Uni<Choice> process(Start in) { return io.smallrye.mutiny.Uni.createFrom().item(new Choice.Fast(new FastPath(in.id()))); }
              }
              public static class FastSvc implements org.pipelineframework.service.ReactiveService<FastPath, Done> {
                public io.smallrye.mutiny.Uni<Done> process(FastPath in) { return io.smallrye.mutiny.Uni.createFrom().item(new Done(in.id())); }
              }
              public static class RootWaitSvc implements org.pipelineframework.service.ReactiveService<BranchA, PendingA> {
                public io.smallrye.mutiny.Uni<PendingA> process(BranchA in) { return io.smallrye.mutiny.Uni.createFrom().item(new PendingA(in.id())); }
              }
              public static class FinishASvc implements org.pipelineframework.service.ReactiveService<AnswerA, Done> {
                public io.smallrye.mutiny.Uni<Done> process(AnswerA in) { return io.smallrye.mutiny.Uni.createFrom().item(new Done(in.id())); }
              }
              public static class NestedSvc implements org.pipelineframework.service.ReactiveService<BranchB, PendingB> {
                public io.smallrye.mutiny.Uni<PendingB> process(BranchB in) { return io.smallrye.mutiny.Uni.createFrom().item(new PendingB(in.id())); }
              }
              public static class DecideLoopSvc implements org.pipelineframework.service.ReactiveService<AnswerB, LoopChoice> {
                public io.smallrye.mutiny.Uni<LoopChoice> process(AnswerB in) { return io.smallrye.mutiny.Uni.createFrom().item(new LoopChoice.Finished(new Done(in.id()))); }
              }
            }
            """;

        PipelinePlacementDescriptor placement = derive(moduleDir, yaml, source, "branch-app");
        assertTrue(placement.present());
        assertEquals(2, placement.invocations().size());
        assertTrue(placement.invocations().stream().allMatch(inv ->
            PipelinePlacementDescriptor.LOCAL_IN_SEGMENT.equals(inv.locality())
                && "branch-app".equals(inv.module())
                && "application".equals(inv.runtimeId())));

        Map<String, ExecutionSegment> byId = placement.executionSegments().stream()
            .collect(Collectors.toMap(ExecutionSegment::segmentId, Function.identity()));
        assertEquals(3, byId.size(), "Segments: " + byId.keySet());

        // Entry can complete directly (FastPath) OR suspend at either branch Await.
        ExecutionSegment entry = byId.get("entry");
        assertTrue(entry.canCompleteRoot());
        assertEquals(java.util.Set.of("$root/Root Await Step", "nested-wait@Call Nested Wait/Nested Await Step"),
            java.util.Set.copyOf(entry.nextAwaitBoundaries()));

        // Root Await branch is mutually exclusive with Nested Await: it finishes directly with NO edge to Nested Await!
        ExecutionSegment resumeRoot = byId.get("resume:$root/Root Await Step");
        assertNotNull(resumeRoot);
        assertTrue(resumeRoot.canCompleteRoot());
        assertEquals(List.of(), resumeRoot.nextAwaitBoundaries());

        // Nested Await branch unwinds to $root and reaches pipeline: self, creating a self-loop back to the nested Await
        // (and potentially root Await on the recursive root re-entry) while also being able to complete root.
        ExecutionSegment resumeNested = byId.get("resume:nested-wait@Call Nested Wait/Nested Await Step");
        assertNotNull(resumeNested);
        assertTrue(resumeNested.canCompleteRoot());
        assertTrue(resumeNested.nextAwaitBoundaries().contains("nested-wait@Call Nested Wait/Nested Await Step"),
            "Expected recursive self-loop edge on nested Await resume segment: " + resumeNested.nextAwaitBoundaries());

        // All three logical segments share ONE physical deployed module.
        assertEquals(java.util.Set.of("branch-app"), placement.deployedModules());
    }

    private void writeColocatedRuntimeMapping(Path moduleDir, String moduleName) throws Exception {
        Path configDir = Files.createDirectories(moduleDir.resolve("config"));
        Files.writeString(configDir.resolve("pipeline.runtime.yaml"), """
            layout: pipeline-runtime
            validation: strict
            runtimes:
              application: {}
            modules:
              %s:
                runtime: application
            defaults:
              runtime: application
              module: %s
              synthetic:
                module: %s
            """.formatted(moduleName, moduleName, moduleName));
    }

    private PipelinePlacementDescriptor derive(Path moduleDir, Path yaml, String source, String moduleName) throws Exception {
        var holder = new PipelinePlacementDescriptor[1];
        var phases = List.<PipelineCompilationPhase>of(
            new PipelineDiscoveryPhase(),
            new PipelineBranchPlanningPhase(),
            new ModelExtractionPhase(),
            new PipelineRuntimeMappingPhase(),
            new PipelineSemanticAnalysisPhase(),
            new PipelineCompilationPhase() {
                @Override public String name() { return "Capture placement"; }
                @Override public void execute(PipelineCompilationContext ctx) {
                    holder[0] = ctx.getPlacementDescriptor();
                }
            });
        var unit = new SimpleJavaFileObject(URI.create("string:///segproof/Types.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        Path classes = Files.createDirectories(moduleDir.resolve("classes"));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, manager, diagnostics, List.of(
                "-proc:only",
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(),
                "-Apipeline.config=" + yaml,
                "-Apipeline.module=" + moduleName,
                "-Apipeline.moduleDir=" + moduleDir,
                "-Apipeline.orchestrator.generate=true"), null, List.of(unit));
            task.setProcessors(List.of(new PipelineStepProcessor(new PipelineCompiler(phases))));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        return holder[0];
    }
}
