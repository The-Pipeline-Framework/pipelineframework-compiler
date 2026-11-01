package org.pipelineframework.processor.renderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.command.CommandDuplicatePolicy;
import org.pipelineframework.connector.*;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.Jsr269SourceInventory;
import org.pipelineframework.processor.PipelineCompilerOptions;
import org.pipelineframework.processor.composition.PipelineReference;
import org.pipelineframework.processor.ir.*;
import org.pipelineframework.processor.util.GeneratedExecutionOrderResolver;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostOperationCommandAwaitRenderingTest {
    @TempDir Path directory;

    @Test
    void emitsSeparateCommandAndCompletionContractsInOperationObserverAwaitOrder() throws Exception {
        var review = ClassName.get("example", "Review");
        var answered = ClassName.get("example", "AnsweredReview");
        var id = ClassName.get("example", "ReviewId");
        var selection = ConnectorOperationSelection.command("Publish review", ConnectorBindingName.of("application"),
            new ConnectorOperationIdentity(ConnectorProviderId.of("example.application"), "review.publish",
                ConnectorOperationKind.COMMAND, 1), 1, Map.of(),
            new ConnectorOperationSelection.CommandSelection(id, CommandDuplicatePolicy.RETURN_RECORDED, CommandPolicy.none()));
        var model = new PipelineStepModel.Builder().serviceName("PublishReviewService")
            .generatedName("PublishReviewService").servicePackage("example")
            .serviceClassName(ClassName.get("org.pipelineframework.command", "CommandStepDescriptor"))
            .inputMapping(TypeMapping.withoutMapper(review)).outputMapping(TypeMapping.withoutMapper(review))
            .streamingShape(StreamingShape.UNARY_UNARY).executionMode(ExecutionMode.DEFAULT)
            .deploymentRole(DeploymentRole.ORCHESTRATOR_CLIENT).cacheKeyGenerator(id)
            .connectorOperationSelection(selection)
            .enabledTargets(Set.of(GenerationTarget.COMMAND_CLIENT_STEP, GenerationTarget.DEFERRED_COMPLETION_STEP))
            .deferredCompletionSelection(new DeferredCompletionSelection(answered, "AnsweredReview", Optional.empty(),
                Duration.ofMinutes(5), List.of(), "interactionId", "interaction-api", Map.of(),
                Optional.empty(), Optional.empty())).build();
        var targetContext = new PipelineCompilationContext(mock(javax.annotation.processing.ProcessingEnvironment.class),
            Jsr269SourceInventory.empty());
        targetContext.setTransportMode(PipelineTransport.LOCAL);
        targetContext.setStepModels(List.of(model));
        new org.pipelineframework.processor.phase.PipelineTargetResolutionPhase().execute(targetContext);
        model = targetContext.getStepModels().getFirst();
        assertEquals(Set.of(GenerationTarget.COMMAND_CLIENT_STEP, GenerationTarget.DEFERRED_COMPLETION_STEP),
            model.enabledTargets());
        var context = mock(GenerationContext.class);
        var options = mock(PipelineCompilerOptions.class);
        when(options.asMap()).thenReturn(Map.of());
        when(context.compilerOptions()).thenReturn(options);
        when(context.outputDir()).thenReturn(directory);
        when(context.role()).thenReturn(DeploymentRole.ORCHESTRATOR_CLIENT);
        when(context.transportMode()).thenReturn(PipelineTransport.LOCAL);
        when(context.pipelineBasePackage()).thenReturn("example");
        when(context.platformMode()).thenReturn(org.pipelineframework.config.PlatformMode.COMPUTE);
        new CommandClientStepRenderer().render(model, context);
        new DeferredCompletionStepRenderer().render(model, context);
        var command = Files.readString(directory.resolve("example/pipeline/PublishReviewCommandClientStep.java"));
        var completion = Files.readString(directory.resolve("example/pipeline/PublishReviewDeferredCompletionStep.java"));
        assertTrue(command.contains("StepOneToOne<Review, Review>"), command);
        assertFalse(command.contains("CommandDeferredCompletionSupport"), command);
        assertTrue(completion.contains("StepOneToOne<Review, AnsweredReview>"), completion);
        var observer = model.toBuilder().serviceName("ReviewObserverService").generatedName("ReviewObserverService")
            .sideEffect(true).aspectPosition(AspectPosition.AFTER_STEP)
            .enabledTargets(Set.of(GenerationTarget.LOCAL_CLIENT_STEP)).deferredCompletionSelection(Optional.empty()).build();
        var compilation = new PipelineCompilationContext(mock(javax.annotation.processing.ProcessingEnvironment.class),
            Jsr269SourceInventory.empty());
        compilation.setTransportMode(PipelineTransport.LOCAL);
        compilation.setStepModels(List.of(model, observer));
        var order = new GeneratedExecutionOrderResolver().weave(compilation, new PipelineReference("$root"),
            List.of("example.pipeline.PublishReviewDeferredCompletionStep"), true);
        assertEquals(List.of("example.pipeline.PublishReviewCommandClientStep",
            "example.pipeline.ReviewObserverLocalClientStep", "example.pipeline.PublishReviewDeferredCompletionStep"), order);
    }
}
