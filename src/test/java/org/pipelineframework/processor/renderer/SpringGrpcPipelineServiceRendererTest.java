package org.pipelineframework.processor.renderer;

import com.google.protobuf.DescriptorProtos;
import com.squareup.javapoet.ClassName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringGrpcPipelineServiceRendererTest {
    @TempDir Path tempDir;

    @Test
    void rendersOnlySpringAndGrpcJavaImportsForExactPairs() throws Exception {
        AtomicReference<Exception> failure = compile("Input", "Output", tempDir.resolve("valid"));
        assertTrue(failure.get() == null, String.valueOf(failure.get()));
        String source = Files.readString(tempDir.resolve("valid/generated/example/pipeline/PipelineGrpcService.java"));
        assertTrue(source.contains("extends PipelineServiceGrpc.PipelineServiceImplBase"));
        assertTrue(source.contains("Mapper<Input, PipelineTypes.Input> inboundMapper"));
        assertTrue(source.contains("Mapper<Output, PipelineTypes.Output> outboundMapper"));
        assertTrue(source.contains("pipelineRunner.run(input)"));
        for (String forbidden : List.of("io.quarkus.", "jakarta.enterprise.", "io.smallrye.mutiny",
                "org.eclipse.microprofile.", "jakarta.ws.rs.")) {
            assertFalse(source.contains(forbidden));
        }
    }

    @Test
    void rejectsMapperWithDifferentDomainMemberOfPair() throws Exception {
        AtomicReference<Exception> failure = compile("Other", "Output", tempDir.resolve("invalid"));
        IllegalStateException error = assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(error.getMessage().contains("must implement Mapper<example.domain.Input, "
            + "example.grpc.PipelineTypes.Input>"), error.getMessage());
    }

    @Test
    void rejectsFinalOutputMapperWithDifferentDomainMemberOfPair() throws Exception {
        AtomicReference<Exception> failure = compile("Input", "Other", tempDir.resolve("invalid-output"));
        IllegalStateException error = assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(error.getMessage().contains("must implement Mapper<example.domain.Output, "
            + "example.grpc.PipelineTypes.Output>"), error.getMessage());
    }

    private static AtomicReference<Exception> compile(String mapperDomain, String outputMapperDomain,
                                                       Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("source"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path generated = Files.createDirectories(root.resolve("generated"));
        Path input = write(source, "example/domain/Input.java",
            "package example.domain; public record Input(String value) {}");
        Path other = write(source, "example/domain/Other.java",
            "package example.domain; public record Other(String value) {}");
        Path output = write(source, "example/domain/Output.java",
            "package example.domain; public record Output(String value) {}");
        Path proto = write(source, "example/grpc/PipelineTypes.java",
            "package example.grpc; public final class PipelineTypes {"
                + " public static final class Input {} public static final class Output {} }");
        Path inbound = write(source, "example/mapper/InputMapper.java",
            "package example.mapper; public final class InputMapper implements "
                + "org.pipelineframework.mapper.Mapper<example.domain." + mapperDomain
                + ", example.grpc.PipelineTypes.Input> {"
                + " public example.domain." + mapperDomain
                + " fromExternal(example.grpc.PipelineTypes.Input value) { throw new UnsupportedOperationException(); }"
                + " public example.grpc.PipelineTypes.Input toExternal(example.domain." + mapperDomain
                + " value) { throw new UnsupportedOperationException(); } }");
        Path outbound = write(source, "example/mapper/OutputMapper.java",
            "package example.mapper; public final class OutputMapper implements "
                + "org.pipelineframework.mapper.Mapper<example.domain." + outputMapperDomain
                + ", example.grpc.PipelineTypes.Output> {"
                + " public example.domain." + outputMapperDomain
                + " fromExternal(example.grpc.PipelineTypes.Output value)"
                + " { throw new UnsupportedOperationException(); }"
                + " public example.grpc.PipelineTypes.Output toExternal(example.domain." + outputMapperDomain
                + " value)"
                + " { throw new UnsupportedOperationException(); } }");
        AtomicReference<Exception> failure = new AtomicReference<>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var units = manager.getJavaFileObjectsFromPaths(List.of(input, other, output, proto, inbound, outbound));
            var task = compiler.getTask(null, manager, null,
                List.of("-proc:only", "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString()), null, units);
            task.setProcessors(List.of(new AbstractProcessor() {
                private boolean rendered;
                @Override public synchronized void init(ProcessingEnvironment environment) {
                    super.init(environment);
                }
                @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
                @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_21; }
                @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                    if (rendered || round.processingOver()) return false;
                    rendered = true;
                    var first = new PipelineStepModel.Builder()
                        .serviceName("ProcessInputService")
                        .servicePackage("example.service")
                        .serviceClassName(ClassName.get("example.service", "InputService"))
                        .streamingShape(StreamingShape.UNARY_UNARY)
                        .executionMode(ExecutionMode.DEFAULT)
                        .deploymentRole(DeploymentRole.PIPELINE_SERVER)
                        .inputMapping(new TypeMapping(ClassName.get("example.domain", "Input"),
                            ClassName.get("example.mapper", "InputMapper"), true))
                        .outputMapping(TypeMapping.withoutMapper(ClassName.get("example.domain", "Other")))
                        .build();
                    var last = new PipelineStepModel.Builder()
                        .serviceName("ProcessOutputService")
                        .servicePackage("example.service")
                        .serviceClassName(ClassName.get("example.service", "OutputService"))
                        .streamingShape(StreamingShape.UNARY_UNARY)
                        .executionMode(ExecutionMode.DEFAULT)
                        .deploymentRole(DeploymentRole.PIPELINE_SERVER)
                        .inputMapping(TypeMapping.withoutMapper(ClassName.get("example.domain", "Other")))
                        .outputMapping(new TypeMapping(ClassName.get("example.domain", "Output"),
                            ClassName.get("example.mapper", "OutputMapper"), true))
                        .build();
                    try {
                        new SpringGrpcPipelineServiceRenderer().render(first, last,
                            Jsr269GenerationContext.create(processingEnv, generated,
                                DeploymentRole.PIPELINE_SERVER, Set.of(), null, descriptor(),
                                PipelineTransport.GRPC, "example"));
                    } catch (Exception exception) {
                        failure.set(exception);
                    }
                    return false;
                }
            }));
            task.call();
        }
        return failure;
    }

    private static Path write(Path root, String name, String contents) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
        return path;
    }

    private static DescriptorProtos.FileDescriptorSet descriptor() {
        var file = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("pipeline-unary.proto")
            .setPackage("example")
            .setOptions(DescriptorProtos.FileOptions.newBuilder()
                .setJavaPackage("example.grpc").setJavaOuterClassname("PipelineTypes"))
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Input"))
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Output"))
            .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder().setName("PipelineService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                    .setName("Run").setInputType(".example.Input").setOutputType(".example.Output")))
            .build();
        return DescriptorProtos.FileDescriptorSet.newBuilder().addFile(file).build();
    }
}
