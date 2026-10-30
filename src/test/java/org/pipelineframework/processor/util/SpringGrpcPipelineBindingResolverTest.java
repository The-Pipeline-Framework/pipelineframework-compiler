package org.pipelineframework.processor.util;

import com.google.protobuf.DescriptorProtos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringGrpcPipelineBindingResolverTest {
    private final GrpcBindingResolver resolver = new GrpcBindingResolver();

    @Test
    void resolvesFirstInputAndFinalOutputFromDescriptor() {
        var method = resolver.resolvePipelineRun(descriptor(false));

        assertEquals("Run", method.getName());
        assertEquals("Input", method.getInputType().getName());
        assertEquals("Receipt", method.getOutputType().getName());
    }

    @Test
    void rejectsMissingAndStreamingRun() {
        assertTrue(assertThrows(IllegalStateException.class,
            () -> resolver.resolvePipelineRun(DescriptorProtos.FileDescriptorSet.getDefaultInstance()))
            .getMessage().contains("descriptor set"));
        assertTrue(assertThrows(IllegalStateException.class,
            () -> resolver.resolvePipelineRun(descriptor(true)))
            .getMessage().contains("unary RPC"));
    }

    private static DescriptorProtos.FileDescriptorSet descriptor(boolean streaming) {
        var file = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("pipeline-unary.proto")
            .setPackage("example")
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Input"))
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Receipt"))
            .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("PipelineService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                    .setName("Run")
                    .setInputType(".example.Input")
                    .setOutputType(".example.Receipt")
                    .setServerStreaming(streaming)))
            .build();
        return DescriptorProtos.FileDescriptorSet.newBuilder().addFile(file).build();
    }
}
