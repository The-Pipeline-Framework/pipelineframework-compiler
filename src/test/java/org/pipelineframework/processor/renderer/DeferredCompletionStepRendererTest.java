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
import org.pipelineframework.processor.ir.DeferredCompletionSelection;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredCompletionStepRendererTest {
    @TempDir Path tempDir;

    @Test
    void rendersCompletionModifierWithDirectDescriptor() throws Exception {
        PipelineStepModel model = model(StreamingShape.UNARY_UNARY);

        var facts = new DeferredCompletionStepRenderer().renderWithDescriptorMetadata(model, context(PipelineTransport.LOCAL), null);

        String source = Files.readString(tempDir.resolve(
            "com/example/approval/pipeline/CreateApprovalDeferredCompletionStep.java"));
        assertEquals(1, facts.get("schemaVersion"));
        assertEquals("CreateApproval", facts.get("stepId"));
        assertEquals("com.example.approval.PendingApproval", facts.get("inputTypeId"));
        assertEquals(false, facts.get("requestAwareCompletion"));
        for (String field : List.of("inputTypeId", "outputTypeId", "transportInputTypeId", "transportOutputTypeId")) {
            assertTrue(source.contains("\"" + facts.get(field) + "\""), field + ": " + source);
        }
        assertTrue(!source.contains("CreateApprovalLocalClientStep operation"));
        assertTrue(source.contains("StepOneToOne<PendingApproval, ApprovalDecision>"));
        assertTrue(source.contains("completionSupport.<PendingApproval, ApprovalDecision>awaitOneToOne(completion, operationOutput)"));
        assertTrue(source.contains("descriptorRegistry.register(new AwaitCompletionDescriptor"));
        assertTrue(source.contains("\"interaction-api\""));
        assertTrue(source.contains("\"orderId\""));
        assertTrue(source.contains("@Startup"));
        assertTrue(source.contains("AwaitStreamOneToOneStep<PendingApproval, ApprovalDecision>"));
    }

    @Test
    void streamingOperationAwaitsEachEmittedResultWithoutAggregateAwait() throws Exception {
        PipelineStepModel model = model(StreamingShape.UNARY_STREAMING);

        new DeferredCompletionStepRenderer().render(model, context(PipelineTransport.GRPC));

        String source = Files.readString(tempDir.resolve(
            "com/example/approval/pipeline/CreateApprovalDeferredCompletionStep.java"));
        assertTrue(!source.contains("CreateApprovalGrpcClientStep operation"));
        assertTrue(source.contains("StepOneToOne<PipelineTypes.PendingApproval, PipelineTypes.ApprovalDecision>"));
        assertTrue(source.contains("awaitOneToOneStream(completion, input)"));
        assertTrue(!source.contains("awaitOneToMany"));
    }

    @Test
    void localGeneratedUnionCompletionUsesPinnedGrpcWireContract() throws Exception {
        PipelineStepModel model = model(StreamingShape.UNARY_STREAMING).toBuilder()
            .inputMapping(TypeMapping.withoutMapper(
                ClassName.get("com.example.approval.domain", "ApprovalRequest")))
            .outputMapping(TypeMapping.withoutMapper(
                ClassName.get("com.example.approval.domain", "PendingApproval")))
            .deferredCompletionSelection(new DeferredCompletionSelection(
                ClassName.get("com.example.approval.domain", "ApprovalDecision"),
                "com.example.approval.domain.ApprovalDecision", Optional.empty(), Duration.ofMinutes(30),
                List.of("orderId"), "interactionId", "kafka", Map.of(), Optional.empty(), Optional.empty()))
            .build();

        new DeferredCompletionStepRenderer().render(model, context(PipelineTransport.LOCAL));

        String source = Files.readString(tempDir.resolve(
            "com/example/approval/pipeline/CreateApprovalDeferredCompletionStep.java"));
        assertTrue(source.contains("StepOneToOne<PendingApproval, ApprovalDecision>"), source);
        assertTrue(source.contains("\"com.example.approval.grpc.PipelineTypes.PendingApproval\""), source);
        assertTrue(source.contains("\"com.example.approval.grpc.PipelineTypes.ApprovalDecision\""), source);
        assertTrue(source.contains("PipelineDomainProtoAdapters.toProto"), source);
        assertTrue(source.contains("PipelineDomainProtoAdapters.fromProto"), source);
    }

    @Test
    void localV3WithoutBasePackageDoesNotConstructGeneratedAdapter() throws Exception {
        PipelineStepModel model = model(StreamingShape.UNARY_UNARY).toBuilder()
            .inputMapping(TypeMapping.withoutMapper(
                ClassName.get("com.example.approval.domain", "ApprovalRequest")))
            .outputMapping(TypeMapping.withoutMapper(
                ClassName.get("com.example.approval.domain", "PendingApproval")))
            .build();
        GenerationContext context = Jsr269GenerationContext.create(
            null, tempDir, DeploymentRole.ORCHESTRATOR_CLIENT, Set.of(),
            null, null, PipelineTransport.LOCAL, "", null, true);

        new DeferredCompletionStepRenderer().render(model, context);

        String source = Files.readString(tempDir.resolve(
            "com/example/approval/pipeline/CreateApprovalDeferredCompletionStep.java"));
        assertTrue(source.contains("StepOneToOne<PendingApproval, ApprovalDecision>"), source);
        assertTrue(!source.contains("PipelineDomainProtoAdapters"), source);
    }

    @Test
    void selectedBranchKeepsCanonicalCompletionAndContinuingOutcomeContracts() throws Exception {
        var before = ClassName.get("com.example.approval.domain", "Before");
        var after = ClassName.get("com.example.approval.domain", "After");
        var review = ClassName.get("com.example.approval.domain", "Review");
        var reply = ClassName.get("com.example.approval.domain", "Reply");
        var branch = new org.pipelineframework.processor.ir.AwaitBranchSelection("Review", review, "Reply", reply,
            new org.pipelineframework.processor.ir.AwaitBranchSelection.CaseAccess(before.nestedClass("ReviewRequired"), "value"),
            Optional.of(after.nestedClass("Answered")), List.of(new org.pipelineframework.processor.ir.AwaitBranchSelection.Bypass(
                new org.pipelineframework.processor.ir.AwaitBranchSelection.CaseAccess(before.nestedClass("Finished"), "value"),
                Optional.of(after.nestedClass("Finished")))));
        var model = model(StreamingShape.UNARY_UNARY).toBuilder()
            .outputMapping(TypeMapping.withoutMapper(before))
            .deferredCompletionSelection(new DeferredCompletionSelection(after, "After", Optional.empty(),
                Duration.ofMinutes(5), List.of(), "interactionId", "interaction-api", Map.of(),
                Optional.empty(), Optional.empty()).withBranchSelection(Optional.of(branch))).build();
        var facts = new DeferredCompletionStepRenderer().renderWithDescriptorMetadata(model, context(PipelineTransport.LOCAL), null);
        assertEquals("Review", facts.get("canonicalInputTypeId"));
        assertEquals("Reply", facts.get("canonicalOutputTypeId"));
        assertEquals("Reply", facts.get("completionPayloadTypeId"));
        assertEquals("com.example.approval.domain.Review", facts.get("inputTypeId"));
        assertEquals("com.example.approval.domain.Reply", facts.get("outputTypeId"));
        String source = Files.readString(tempDir.resolve(
            "com/example/approval/pipeline/CreateApprovalDeferredCompletionStep.java"));
        assertTrue(source.contains("StepOneToOne<Before, After>"), source);
        assertTrue(source.contains("awaitOneToOne(completion, value.value())"), source);
        assertTrue(source.contains("new After.Finished(value.value())"), source);
        assertTrue(source.contains("map(reply -> new After.Answered(reply))"), source);
        assertTrue(source.contains("\"com.example.approval.domain.Review\", \"com.example.approval.domain.Reply\""), source);
        assertTrue(!source.contains("PipelineDomainProtoAdapters"), source);
    }

    @Test
    void retainsExplicitLegacyPayloadIdentityWhenNoCanonicalAliasWasNormalized() throws Exception {
        var model = model(StreamingShape.UNARY_UNARY).toBuilder()
            .deferredCompletionSelection(new DeferredCompletionSelection(
                ClassName.get("example", "Result"), "Result", Optional.empty(), Duration.ofMinutes(1),
                List.of(), "interactionId", "interaction-api", Map.of(),
                Optional.of(ClassName.get("example", "Reply")), Optional.of(ClassName.get("example", "Projector"))))
            .build();
        var facts = new DeferredCompletionStepRenderer().renderWithDescriptorMetadata(model, context(PipelineTransport.LOCAL), null);
        assertEquals("example.Reply", facts.get("completionPayloadTypeId"));
        assertEquals("Result", facts.get("canonicalOutputTypeId"));
        assertEquals("example.Projector", facts.get("completionProjectorId"));
    }

    @Test
    void exposesDeferredCompletionTarget() {
        assertEquals(GenerationTarget.DEFERRED_COMPLETION_STEP, new DeferredCompletionStepRenderer().target());
    }

    private PipelineStepModel model(StreamingShape shape) {
        return new PipelineStepModel.Builder()
            .serviceName("CreateApproval")
            .generatedName("CreateApprovalService")
            .servicePackage("com.example.approval")
            .serviceClassName(ClassName.get("com.example.approval", "CreateApprovalService"))
            .streamingShape(shape)
            .executionMode(ExecutionMode.DEFAULT)
            .inputMapping(TypeMapping.withoutMapper(ClassName.get("com.example.approval", "ApprovalRequest")))
            .outputMapping(TypeMapping.withoutMapper(ClassName.get("com.example.approval", "PendingApproval")))
            .enabledTargets(Set.of(GenerationTarget.DEFERRED_COMPLETION_STEP))
            .deploymentRole(DeploymentRole.ORCHESTRATOR_CLIENT)
            .deferredCompletionSelection(new DeferredCompletionSelection(
                ClassName.get("com.example.approval", "ApprovalDecision"), "ApprovalDecision", Optional.empty(),
                Duration.ofMinutes(30),
                List.of("orderId"), "interactionId", "interaction-api", Map.of(),
                Optional.empty(), Optional.empty()))
            .build();
    }

    private GenerationContext context(PipelineTransport transport) {
        return Jsr269GenerationContext.create(null, tempDir, DeploymentRole.ORCHESTRATOR_CLIENT, Set.of(),
            null, null, transport, "com.example.approval", null, true);
    }
}
