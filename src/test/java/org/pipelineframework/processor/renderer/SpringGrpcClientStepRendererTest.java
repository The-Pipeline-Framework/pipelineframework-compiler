package org.pipelineframework.processor.renderer;

import com.google.protobuf.DescriptorProtos;
import com.squareup.javapoet.ClassName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.processor.PipelineStepProcessor;
import org.pipelineframework.processor.ir.*;
import org.pipelineframework.processor.util.GrpcBindingResolver;

import static org.junit.jupiter.api.Assertions.*;

class SpringGrpcClientStepRendererTest {
    @TempDir Path root;

    @Test
    void compilesGeneratedClientAndQualifiedStubConfiguration() throws Exception {
        var failure = render("Input", "Input", false);
        assertNull(failure.get());
        var step = Files.readString(root.resolve("generated-sources/example/service/pipeline/ProcessRemoteGrpcClientStep.java"));
        assertTrue(step.contains("@Order(7)"));
        assertTrue(step.contains("implements PipelineUnaryStep<Input, Output>"));
        assertTrue(step.contains("ProcessRemoteServiceGrpc.ProcessRemoteServiceFutureStub"));
        assertTrue(step.contains("inboundMapper.toExternal(input)"));
        assertTrue(step.contains("outboundMapper.fromExternal(response)"));
        assertTrue(step.contains("call.cancel(true)"));
        assertTrue(step.contains("TpfContextHeaders.CACHE_POLICY"));
        for (String token : List.of("io.quarkus.", "jakarta.enterprise.", "io.smallrye.mutiny",
            "org.eclipse.microprofile.", "jakarta.ws.rs.")) assertFalse(step.contains(token));
        compileGenerated();
    }

    @Test
    void rejectsWrongDomainAndWrongProtobufMapperPairs() throws Exception {
        var error = render("Other", "Input", false).get();
        assertInstanceOf(IllegalStateException.class, error);
        assertTrue(error.getMessage().contains("Mapper<example.domain.Input, example.grpc.PipelineTypes.Input>"));
        error = render("Input", "Output", false).get();
        assertInstanceOf(IllegalStateException.class, error);
        assertTrue(error.getMessage().contains("Mapper<example.domain.Input, example.grpc.PipelineTypes.Input>"));
    }

    @Test
    void rejectsWrongOutputDomainPair() throws Exception {
        var error = render("Input", "Input", false, "Other").get();
        assertInstanceOf(IllegalStateException.class, error);
        assertTrue(error.getMessage().contains("Mapper<example.domain.Output, example.grpc.PipelineTypes.Output>"));
    }

    @Test
    void rejectsMissingMapperForAnAuthoredDomain() throws Exception {
        var error = render("Input", "Input", true, "Output", false).get();
        assertInstanceOf(IllegalStateException.class, error);
        assertTrue(error.getMessage().contains("requires a Mapper<example.domain.Input, example.grpc.PipelineTypes.Input>"));
    }

    @Test
    void usesGeneratedCanonicalAdapters() throws Exception {
        assertNull(render("Input", "Input", true).get());
        String step = Files.readString(root.resolve("generated-sources/example/service/pipeline/ProcessRemoteGrpcClientStep.java"));
        assertTrue(step.contains("PipelineDomainProtoAdapters.toProto(input)"));
        assertFalse(step.contains("inboundMapper"));
        compileGenerated();
    }

    @Test
    void clientOnlyYamlCompilationNeedsNoPipelineRunDescriptorOrServer() throws Exception {
        sources("Input", "Input");
        Path descriptor = root.resolve("downstream.pb");
        Files.write(descriptor, descriptor(false).toByteArray());
        Path yaml = root.resolve("pipeline.yaml");
        Files.writeString(yaml, """
            version: 2
            appName: Client only
            basePackage: example
            transport: GRPC
            platform: COMPUTE
            types:
              Input:
                fields:
                  - [value, string]
              Output:
                fields:
                  - [value, string]
            steps:
              - name: Remote
                cardinality: ONE_TO_ONE
                service: example.service.AbsentRemoteService
                input: Input
                output: Output
                java:
                  input: example.domain.Input
                  output: example.domain.Output
                inboundMapper: example.mapper.InputMapper
                outboundMapper: example.mapper.OutputMapper
            """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var task = compiler.getTask(null, manager, null, List.of("-proc:only", "-classpath",
                System.getProperty("java.class.path"), "-d", Files.createDirectories(root.resolve("classes")).toString(),
                "-Apipeline.config=" + yaml, "-Apipeline.transport=GRPC", "-Apipeline.platform=COMPUTE",
                "-Apipeline.codegen.rendererProfile=spring", "-Aprotobuf.descriptor.file=" + descriptor,
                "-Apipeline.generatedSourcesDir=" + root.resolve("generated-sources")), null,
                manager.getJavaFileObjectsFromPaths(javaSources(root.resolve("source"))));
            task.setProcessors(List.of(new PipelineStepProcessor()));
            assertTrue(task.call());
        }
        List<Path> generated = javaSources(root.resolve("generated-sources"));
        assertTrue(generated.stream().anyMatch(path -> path.getFileName().toString().equals("ProcessRemoteGrpcClientStep.java")));
        assertFalse(generated.stream().anyMatch(path -> path.getFileName().toString().equals("PipelineGrpcService.java")));
        compileGenerated();
    }

    @Test
    void rejectsStreamingAndMissingRemoteDescriptors() {
        var resolver = new GrpcBindingResolver();
        assertThrows(IllegalStateException.class, () -> resolver.resolve(model(false), descriptor(true)));
        assertThrows(IllegalStateException.class,
            () -> resolver.resolve(model(false), DescriptorProtos.FileDescriptorSet.getDefaultInstance()));
        var noMethod = descriptor(false).toBuilder();
        noMethod.getFileBuilder(0).getServiceBuilder(0).clearMethod();
        assertThrows(IllegalStateException.class, () -> resolver.resolve(model(false), noMethod.build()));
    }

    private AtomicReference<Exception> render(String mapperDomain, String mapperProto, boolean canonical) throws Exception {
        return render(mapperDomain, mapperProto, canonical, "Output");
    }

    private AtomicReference<Exception> render(String mapperDomain, String mapperProto, boolean canonical,
                                              String outputDomain) throws Exception {
        return render(mapperDomain, mapperProto, canonical, outputDomain, canonical);
    }

    private AtomicReference<Exception> render(String mapperDomain, String mapperProto, boolean canonical,
                                              String outputDomain, boolean generatedDomain) throws Exception {
        sources(mapperDomain, mapperProto, outputDomain);
        var failure = new AtomicReference<Exception>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var task = compiler.getTask(null, manager, null, List.of("-proc:only", "-classpath",
                System.getProperty("java.class.path"), "-d", Files.createDirectories(root.resolve("classes")).toString()),
                null, manager.getJavaFileObjectsFromPaths(javaSources(root.resolve("source"))));
            task.setProcessors(List.of(new AbstractProcessor() {
                private boolean rendered;
                @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
                @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_21; }
                @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                    if (rendered || round.processingOver()) return false;
                    rendered = true;
                    try {
                        var binding = new GrpcBindingResolver().resolve(model(canonical), descriptor(false));
                        new SpringGrpcClientStepRenderer().render(binding, Jsr269GenerationContext.create(processingEnv,
                            root.resolve("generated-sources"), DeploymentRole.ORCHESTRATOR_CLIENT, Set.of(), null,
                            descriptor(false), PipelineTransport.GRPC, "example", 7, generatedDomain));
                    } catch (Exception exception) { failure.set(exception); }
                    return false;
                }
            }));
            assertTrue(task.call());
        }
        return failure;
    }

    private void compileGenerated() throws Exception {
        List<Path> files = new ArrayList<>(javaSources(root.resolve("source")));
        files.addAll(javaSources(root.resolve("generated-sources")));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", root.resolve("classes").toString()), null,
                manager.getJavaFileObjectsFromPaths(files)).call());
        }
    }

    private List<Path> javaSources(Path directory) throws Exception {
        try (var files = Files.walk(directory)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private void sources(String mapperDomain, String mapperProto) throws Exception {
        sources(mapperDomain, mapperProto, "Output");
    }

    private void sources(String mapperDomain, String mapperProto, String outputDomain) throws Exception {
        for (String name : List.of("Input", "Output", "Other"))
            write("example/domain/" + name + ".java", "package example.domain; public record " + name + "(String value) {}");
        write("example/grpc/PipelineTypes.java", "package example.grpc; public final class PipelineTypes {"
            + " public static class Input {} public static class Output {} }");
        write("example/mapper/InputMapper.java", "package example.mapper; public class InputMapper implements "
            + "org.pipelineframework.mapper.Mapper<example.domain." + mapperDomain + ", example.grpc.PipelineTypes." + mapperProto + "> {"
            + " public example.domain." + mapperDomain + " fromExternal(example.grpc.PipelineTypes." + mapperProto + " v) { throw new UnsupportedOperationException(); }"
            + " public example.grpc.PipelineTypes." + mapperProto + " toExternal(example.domain." + mapperDomain + " v) { throw new UnsupportedOperationException(); }}");
        write("example/mapper/OutputMapper.java", "package example.mapper; public class OutputMapper implements "
            + "org.pipelineframework.mapper.Mapper<example.domain." + outputDomain + ", example.grpc.PipelineTypes.Output> {"
            + " public example.domain." + outputDomain + " fromExternal(example.grpc.PipelineTypes.Output v) { throw new UnsupportedOperationException(); }"
            + " public example.grpc.PipelineTypes.Output toExternal(example.domain." + outputDomain + " v) { throw new UnsupportedOperationException(); }}");
        write("example/domain/PipelineDomainProtoAdapters.java", """
            package example.domain;
            public class PipelineDomainProtoAdapters {
                public static example.grpc.PipelineTypes.Input toProto(Input value) { throw new UnsupportedOperationException(); }
                public static Output fromProto(example.grpc.PipelineTypes.Output value) { throw new UnsupportedOperationException(); }
            }
            """);
        write("example/grpc/ProcessRemoteServiceGrpc.java", """
            package example.grpc;
            public class ProcessRemoteServiceGrpc {
                public static ProcessRemoteServiceFutureStub newFutureStub(io.grpc.Channel channel) { return new ProcessRemoteServiceFutureStub(); }
                public static class ProcessRemoteServiceFutureStub {
                    public ProcessRemoteServiceFutureStub withInterceptors(io.grpc.ClientInterceptor... interceptors) { return this; }
                    public com.google.common.util.concurrent.ListenableFuture<PipelineTypes.Output> remoteProcess(PipelineTypes.Input value) {
                        return com.google.common.util.concurrent.Futures.immediateFuture(new PipelineTypes.Output());
                    }
                }
            }
            """);
    }

    private void write(String name, String content) throws Exception {
        Path file = root.resolve("source").resolve(name);
        Files.createDirectories(file.getParent()); Files.writeString(file, content);
    }

    private PipelineStepModel model(boolean canonical) {
        var builder = new PipelineStepModel.Builder().serviceName("ProcessRemoteService").generatedName("ProcessRemoteService")
            .servicePackage("example.service").serviceClassName(ClassName.get("example.service", "AbsentRemoteService"))
            .streamingShape(StreamingShape.UNARY_UNARY)
            .executionMode(ExecutionMode.DEFAULT).deploymentRole(DeploymentRole.ORCHESTRATOR_CLIENT)
            .inputMapping(new TypeMapping(ClassName.get("example.domain", "Input"), ClassName.get("example.mapper", "InputMapper"), true))
            .outputMapping(new TypeMapping(ClassName.get("example.domain", "Output"), ClassName.get("example.mapper", "OutputMapper"), true));
        if (canonical) builder.inputMapping(TypeMapping.withoutMapper(ClassName.get("example.domain", "Input")))
            .outputMapping(TypeMapping.withoutMapper(ClassName.get("example.domain", "Output")));
        return builder.build();
    }

    private DescriptorProtos.FileDescriptorSet descriptor(boolean streaming) {
        var file = DescriptorProtos.FileDescriptorProto.newBuilder().setName("remote.proto").setPackage("wire")
            .setOptions(DescriptorProtos.FileOptions.newBuilder().setJavaPackage("example.grpc").setJavaOuterClassname("PipelineTypes"))
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Input"))
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Output"))
            .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder().setName("ProcessRemoteService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder().setName("remoteProcess")
                    .setInputType(".wire.Input").setOutputType(".wire.Output").setServerStreaming(streaming)));
        return DescriptorProtos.FileDescriptorSet.newBuilder().addFile(file).build();
    }
}
