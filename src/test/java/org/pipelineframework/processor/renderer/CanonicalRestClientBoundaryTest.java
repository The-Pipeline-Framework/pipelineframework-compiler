package org.pipelineframework.processor.renderer;

import static org.junit.jupiter.api.Assertions.*;

import com.squareup.javapoet.ClassName;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.PlatformMode;
import org.pipelineframework.config.template.*;
import org.pipelineframework.processor.ir.*;

/** Executes the generated clients: canonical values must survive every REST cardinality. */
class CanonicalRestClientBoundaryTest {
    @TempDir Path directory;

    @Test
    void generatedClientsRoundTripCanonicalValuesAndKeepWireDtosAtHttpBoundary() throws Exception {
        Path sources = directory.resolve("sources"), generated = directory.resolve("generated"), classes = directory.resolve("classes");
        Files.createDirectories(generated);
        Files.createDirectories(classes);
        source(sources, "example/domain/Document.java", "package example.domain; public record Document(String id) {}\n");
        var model = new PipelineTemplateTypeModel(Map.of("Document", new PipelineTemplateTypeDefinition.RecordType(
            "Document", List.of(new PipelineTemplateTypeDefinition.Field("id", new PipelineTemplateTypeReference.Scalar("string"))))));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, files, diagnostics, List.of("-proc:only", "-d", classes.toString(),
                "-classpath", System.getProperty("java.class.path")), null,
                files.getJavaFileObjects(sources.resolve("example/domain/Document.java").toFile()));
            task.setProcessors(List.of(new AbstractProcessor() {
                boolean rendered;
                public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
                public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_21; }
                public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                    if (rendered || round.processingOver()) return false;
                    rendered = true;
                    try {
                        var context = Jsr269GenerationContext.create(processingEnv, generated,
                            DeploymentRole.ORCHESTRATOR_CLIENT, Set.of(), null, null, PipelineTransport.REST,
                            "example", null, true, Optional.of(model));
                        for (var platform : PlatformMode.values()) for (var shape : StreamingShape.values()) {
                            String name = platform.name() + shape.name();
                            var step = step(name, shape);
                            new RestClientStepRenderer().render(new RestBinding(step, "/documents"), context.withPlatformMode(platform));
                        }
                        new RestClientStepRenderer().render(new RestBinding(step("Paged", StreamingShape.UNARY_STREAMING)
                            .toBuilder().pagedSource(true).build(), "/documents"), context);
                        var persistence = step("PersistenceDocumentSideEffect", StreamingShape.UNARY_UNARY)
                            .toBuilder().sideEffect(true).build();
                        new RestClientStepRenderer().render(new RestBinding(persistence, "/persistence"), context);
                        var config = org.mockito.Mockito.mock(PipelineTemplateConfig.class);
                        org.mockito.Mockito.when(config.dialect()).thenReturn(PipelineTemplateDialect.V3);
                        org.mockito.Mockito.when(config.typeModel()).thenReturn(model);
                        org.mockito.Mockito.when(config.basePackage()).thenReturn("example");
                        new CanonicalRestCacheReaderRenderer().render(config, context);
                        var await = step("Await", StreamingShape.UNARY_STREAMING).toBuilder()
                            .deferredCompletionSelection(new DeferredCompletionSelection(
                                ClassName.get("example.domain", "Document"), "Document", Optional.empty(),
                                Duration.ofMinutes(1), List.of("id"), "id", "sqs", Map.of(),
                                Optional.empty(), Optional.empty())).build();
                        new DeferredCompletionStepRenderer().render(await, context);
                        var step = step("Input", StreamingShape.UNARY_UNARY);
                        new AwsDurableInputDecoderRenderer().render(new OrchestratorBinding(step, "example", "REST",
                            "Document", "Document", false, false, "InputService", StreamingShape.UNARY_UNARY,
                            null, null, null), context);
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                    return false;
                }
            }));
            assertTrue(Boolean.TRUE.equals(task.call()), diagnostics.getDiagnostics().toString());
        }
        runtimeSignatures(sources);
        try (var files = compiler.getStandardFileManager(diagnostics, null, null);
             var authored = Files.walk(sources); var emitted = Files.walk(generated)) {
            var all = new ArrayList<Path>();
            all.addAll(authored.filter(p -> p.toString().endsWith(".java")).toList());
            all.addAll(emitted.filter(p -> p.toString().endsWith(".java")).toList());
            assertTrue(Boolean.TRUE.equals(compiler.getTask(null, files, diagnostics,
                List.of("-proc:none", "-d", classes.toString(), "-classpath", System.getProperty("java.class.path")),
                null, files.getJavaFileObjectsFromPaths(all)).call()), diagnostics.getDiagnostics().toString());
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> domain = loader.loadClass("example.domain.Document");
            Class<?> dto = loader.loadClass("example.dto.DocumentDto");
            Object input = domain.getConstructor(String.class).newInstance("first");
            Object second = domain.getConstructor(String.class).newInstance("second");
            for (var platform : PlatformMode.values()) for (var shape : StreamingShape.values()) {
                String name = platform.name() + shape.name();
                Class<?> client = loader.loadClass("example.service.pipeline." + name + "RestClient");
                Class<?> step = loader.loadClass("example.service.pipeline." + name + "RestClientStep");
                Object instance = step.getConstructor().newInstance();
                Object transport = Proxy.newProxyInstance(loader, new Class<?>[]{client}, (proxy, method, args) -> {
                    Object request = args[3];
                    List<?> values = request instanceof List<?> list ? list : request instanceof Multi<?> multi
                        ? multi.collect().asList().await().atMost(Duration.ofSeconds(2)) : List.of(request);
                    assertTrue(values.stream().allMatch(dto::isInstance), "HTTP client must receive DTOs for " + shape);
                    if (shape == StreamingShape.UNARY_UNARY || shape == StreamingShape.STREAMING_UNARY)
                        return Uni.createFrom().item(values.getFirst());
                    if (shape == StreamingShape.UNARY_STREAMING && platform == PlatformMode.FUNCTION)
                        return Uni.createFrom().item(List.of(values.getFirst(), dto.getConstructor(String.class).newInstance("second")));
                    if (shape == StreamingShape.UNARY_STREAMING)
                        return Multi.createFrom().items(values.getFirst(), dto.getConstructor(String.class).newInstance("second"));
                    return Multi.createFrom().iterable(values);
                });
                inject(instance, "restClient", transport);
                inject(instance, "invocationRuntime", loader.loadClass("org.pipelineframework.invocation.PipelineInvocationRuntime").getConstructor().newInstance());
                String method = switch (shape) {
                    case UNARY_UNARY -> "applyOneToOne";
                    case UNARY_STREAMING -> "applyOneToMany";
                    case STREAMING_UNARY -> "applyReduce";
                    case STREAMING_STREAMING -> "applyTransform";
                };
                Object output = step.getMethod(method, shape == StreamingShape.UNARY_UNARY || shape == StreamingShape.UNARY_STREAMING
                    ? domain : Multi.class).invoke(instance, shape == StreamingShape.UNARY_UNARY || shape == StreamingShape.UNARY_STREAMING
                        ? input : Multi.createFrom().items(input, second));
                List<?> result = output instanceof Uni<?> uni ? List.of(uni.await().atMost(Duration.ofSeconds(2)))
                    : ((Multi<?>) output).collect().asList().await().atMost(Duration.ofSeconds(2));
                assertEquals(shape == StreamingShape.UNARY_UNARY || shape == StreamingShape.STREAMING_UNARY
                    ? List.of(input) : List.of(input, second), result, platform + "/" + shape);
                if (shape == StreamingShape.UNARY_UNARY)
                    assertEquals(domain, step.getMethod("cacheKeyTargetType").invoke(instance));
            }
            Class<?> pagedClient = loader.loadClass("example.service.pipeline.PagedRestClient");
            Object paged = loader.loadClass("example.service.pipeline.PagedRestClientStep").getConstructor().newInstance();
            inject(paged, "restClient", Proxy.newProxyInstance(loader, new Class<?>[]{pagedClient}, (proxy, method, args) -> {
                assertEquals("openPage", method.getName());
                Object wireRequest = args[0];
                assertInstanceOf(dto, wireRequest.getClass().getMethod("input").invoke(wireRequest));
                assertEquals("source", wireRequest.getClass().getMethod("sourceIdentity").invoke(wireRequest));
                assertEquals(Optional.of("checkpoint"), wireRequest.getClass().getMethod("checkpoint").invoke(wireRequest));
                assertEquals(5, wireRequest.getClass().getMethod("maxRecords").invoke(wireRequest));
                Class<?> frame = loader.loadClass("org.pipelineframework.paging.RemotePageWireFrame");
                return Multi.createFrom().items(
                    frame.getConstructor(Optional.class, Optional.class).newInstance(Optional.of(dto.getConstructor(String.class).newInstance("first")), Optional.empty()),
                    frame.getConstructor(Optional.class, Optional.class).newInstance(Optional.empty(), Optional.of("done")));
            }));
            inject(paged, "invocationRuntime", loader.loadClass("org.pipelineframework.invocation.PipelineInvocationRuntime").getConstructor().newInstance());
            inject(paged, "pageBridge", loader.loadClass("org.pipelineframework.paging.RemotePagedSourceBridge").getConstructor().newInstance());
            Class<?> pageRequest = loader.loadClass("org.pipelineframework.paging.PagedSourceRequest");
            Object request = pageRequest.getConstructor(Object.class, String.class, Optional.class, int.class)
                .newInstance(input, "source", Optional.of("checkpoint"), 5);
            Object page = paged.getClass().getMethod("openPage", pageRequest).invoke(paged, request);
            assertEquals(List.of(input), ((Multi<?>) page.getClass().getMethod("items").invoke(page))
                .collect().asList().await().atMost(Duration.ofSeconds(2)));
            Class<?> cacheReader = loader.loadClass("org.pipelineframework.cache.PipelineCacheReader");
            var cachedValue = new java.util.concurrent.atomic.AtomicReference<Object>(dto.getConstructor(String.class).newInstance("first"));
            Object delegate = Proxy.newProxyInstance(loader, new Class<?>[]{cacheReader}, (proxy, method, args) -> switch (method.getName()) {
                case "get" -> Uni.createFrom().item(Optional.ofNullable(cachedValue.get()));
                case "exists" -> Uni.createFrom().item(cachedValue.get() != null);
                case "priority" -> 42;
                default -> throw new AssertionError(method);
            });
            Object decorator = loader.loadClass("example.transport.generated.CanonicalRestCacheReaderDecorator").getConstructor().newInstance();
            inject(decorator, "delegate", delegate);
            assertEquals(42, decorator.getClass().getMethod("priority").invoke(decorator));
            assertEquals(Boolean.TRUE, ((Uni<?>) decorator.getClass().getMethod("exists", String.class).invoke(decorator, "warm")).await().atMost(Duration.ofSeconds(2)));
            Object replay = ((Optional<?>) ((Uni<?>) decorator.getClass().getMethod("get", String.class)
                .invoke(decorator, "warm")).await().atMost(Duration.ofSeconds(2))).orElseThrow();
            assertEquals(input, replay);
            Class<?> persistenceClient = loader.loadClass("example.service.pipeline.PersistenceDocumentSideEffectRestClient");
            Object persistence = loader.loadClass("example.service.pipeline.PersistenceDocumentSideEffectRestClientStep").getConstructor().newInstance();
            inject(persistence, "restClient", Proxy.newProxyInstance(loader, new Class<?>[]{persistenceClient}, (proxy, method, args) -> {
                assertInstanceOf(dto, args[3]);
                return Uni.createFrom().item(args[3]);
            }));
            inject(persistence, "invocationRuntime", loader.loadClass("org.pipelineframework.invocation.PipelineInvocationRuntime").getConstructor().newInstance());
            assertEquals(input, ((Uni<?>) persistence.getClass().getMethod("applyOneToOne", domain)
                .invoke(persistence, replay)).await().atMost(Duration.ofSeconds(2)), "warm DTO cache value must reach canonical persistence sideeffect");
            for (Object retained : List.of(input, "negative-marker", List.of("unrelated"))) {
                cachedValue.set(retained);
                assertSame(retained, ((Optional<?>) ((Uni<?>) decorator.getClass().getMethod("get", String.class)
                    .invoke(decorator, "retained")).await().atMost(Duration.ofSeconds(2))).orElseThrow());
            }
            cachedValue.set(null);
            assertEquals(Optional.empty(), ((Uni<?>) decorator.getClass().getMethod("get", String.class)
                .invoke(decorator, "miss")).await().atMost(Duration.ofSeconds(2)));
            Object awaited = loader.loadClass("example.service.pipeline.AwaitDeferredCompletionStep").getConstructor().newInstance();
            inject(awaited, "descriptorRegistry", loader.loadClass("org.pipelineframework.awaitable.AwaitCompletionDescriptorRegistry").getConstructor().newInstance());
            awaited.getClass().getMethod("registerCompletion").invoke(awaited);
            var descriptorField = awaited.getClass().getDeclaredField("completion");
            descriptorField.setAccessible(true);
            Object descriptor = descriptorField.get(awaited);
            assertEquals(domain.getName(), descriptor.getClass().getMethod("inputType").invoke(descriptor));
            assertEquals(domain.getName(), descriptor.getClass().getMethod("outputType").invoke(descriptor));
            assertEquals(dto.getName(), descriptor.getClass().getMethod("transportInputType").invoke(descriptor));
            assertEquals(dto.getName(), descriptor.getClass().getMethod("transportOutputType").invoke(descriptor));
            @SuppressWarnings("unchecked")
            var toWire = (java.util.function.Function<Object,Object>) descriptor.getClass().getMethod("inputToTransport").invoke(descriptor);
            @SuppressWarnings("unchecked")
            var fromWire = (java.util.function.Function<Object,Object>) descriptor.getClass().getMethod("outputFromTransport").invoke(descriptor);
            Object dispatched = toWire.apply(input);
            assertInstanceOf(dto, dispatched);
            assertEquals(input, fromWire.apply(dispatched));
            Object decoder = loader.loadClass("example.orchestrator.service.AwsDurablePipelineInputDecoder").getConstructor().newInstance();
            inject(decoder, "mapper", new com.fasterxml.jackson.databind.ObjectMapper());
            assertEquals(input, decoder.getClass().getMethod("decode", String.class).invoke(decoder, "{\"id\":\"first\"}"));
        }
    }

    private PipelineStepModel step(String name, StreamingShape shape) {
        var type = ClassName.get("example.domain", "Document");
        return new PipelineStepModel.Builder().serviceName(name + "Service").servicePackage("example.service")
            .serviceClassName(ClassName.get("example.service", name + "Service")).streamingShape(shape)
            .executionMode(ExecutionMode.DEFAULT).inputMapping(TypeMapping.canonical(type, "Document"))
            .outputMapping(TypeMapping.canonical(type, "Document")).build();
    }

    private void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private void source(Path root, String name, String body) throws Exception {
        Path path = root.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, body);
    }

    private void stub(Path root, String name, String body) throws Exception {
        int split = name.lastIndexOf('.');
        source(root, name.replace('.', '/') + ".java", "package " + name.substring(0, split) + ";\n" + body);
    }

    private void runtimeSignatures(Path root) throws Exception {
        // Boundary-only signatures: execution uses real Mutiny, generated DTOs and exact generated mappers.
        for (String name : List.of("jakarta.enterprise.context.Dependent", "jakarta.enterprise.context.ApplicationScoped",
            "jakarta.inject.Inject", "jakarta.decorator.Decorator", "jakarta.decorator.Delegate", "jakarta.enterprise.inject.Any", "jakarta.annotation.PostConstruct", "io.quarkus.runtime.Startup", "io.quarkus.arc.Unremovable", "jakarta.ws.rs.POST"))
            stub(root, name, "public @interface " + name.substring(name.lastIndexOf('.') + 1) + " {}");
        for (String name : List.of("jakarta.ws.rs.Path", "jakarta.ws.rs.HeaderParam", "org.jboss.resteasy.reactive.RestStreamElementType"))
            stub(root, name, "public @interface " + name.substring(name.lastIndexOf('.') + 1) + " { String value(); }");
        for (String name : List.of("jakarta.ws.rs.Consumes", "jakarta.ws.rs.Produces"))
            stub(root, name, "public @interface " + name.substring(name.lastIndexOf('.') + 1) + " { String[] value(); }");
        stub(root, "org.eclipse.microprofile.rest.client.inject.RegisterRestClient", "public @interface RegisterRestClient { String configKey(); }");
        stub(root, "org.eclipse.microprofile.rest.client.inject.RestClient", "public @interface RestClient {}");
        for (String name : List.of("RegisterClientHeaders", "RegisterProvider"))
            stub(root, "org.eclipse.microprofile.rest.client.annotation." + name, "public @interface " + name + " { Class<?> value(); }");
        for (String name : List.of("PipelineContextClientHeadersFactory", "PipelineCacheStatusClientResponseFilter"))
            stub(root, "org.pipelineframework.context.rest." + name, "public class " + name + " {}");
        stub(root, "org.pipelineframework.context.PipelineContext", "public record PipelineContext(String versionTag, String replayMode, String cachePolicy) {}");
        stub(root, "org.pipelineframework.context.PipelineContextHolder", "public class PipelineContextHolder { public static PipelineContext get() { return null; } }");
        stub(root, "org.pipelineframework.context.PipelineContextHeaders", "public class PipelineContextHeaders { public static final String VERSION=\"v\", REPLAY=\"r\", CACHE_POLICY=\"c\"; }");
        stub(root, "jakarta.annotation.Priority", "public @interface Priority { int value(); }");
        stub(root, "org.pipelineframework.cache.PipelineCacheReader", "public interface PipelineCacheReader { io.smallrye.mutiny.Uni<java.util.Optional<Object>> get(String key); io.smallrye.mutiny.Uni<Boolean> exists(String key); default int priority() { return 0; } }");
        stub(root, "org.pipelineframework.invocation.TransportBoundaryDescriptor", "public record TransportBoundaryDescriptor(String protocol, String target) {}");
        stub(root, "org.pipelineframework.invocation.TransportBoundaryInvocation", "public interface TransportBoundaryInvocation { TransportBoundaryDescriptor transportBoundary(); }");
        stub(root, "org.pipelineframework.invocation.PipelineInvocationRuntime", "public class PipelineInvocationRuntime { public <T> io.smallrye.mutiny.Uni<T> invokeTransportUni(Object owner, java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> call) { return call.get(); } public <T> io.smallrye.mutiny.Multi<T> invokeTransportMulti(Object owner, java.util.function.Supplier<io.smallrye.mutiny.Multi<T>> call) { return call.get(); } }");
        stub(root, "org.pipelineframework.telemetry.HttpMetrics", "public class HttpMetrics { public static <T> T instrumentClient(String name, String op, T value) { return value; } }");
        stub(root, "org.pipelineframework.awaitable.AwaitCompletionDescriptor", "public record AwaitCompletionDescriptor(String stepId, String inputType, String outputType, String cardinality, java.time.Duration timeout, String correlationStrategy, String transportType, java.util.Map<String,Object> transportConfig, java.util.List<String> keys, String transportInputType, String transportOutputType, java.util.function.Function<Object,Object> inputToTransport, java.util.function.Function<Object,Object> outputFromTransport) {}");
        stub(root, "org.pipelineframework.awaitable.AwaitCompletionDescriptorRegistry", "public class AwaitCompletionDescriptorRegistry { public AwaitCompletionDescriptor register(AwaitCompletionDescriptor descriptor) { return descriptor; } }");
        stub(root, "org.pipelineframework.awaitable.AwaitCompletionSupport", "public class AwaitCompletionSupport { public <I,O> io.smallrye.mutiny.Uni<O> awaitOneToOne(AwaitCompletionDescriptor descriptor, I input) { return null; } public <I,O> io.smallrye.mutiny.Multi<O> awaitOneToOneStream(AwaitCompletionDescriptor descriptor, io.smallrye.mutiny.Multi<I> input) { return null; } }");
        stub(root, "org.pipelineframework.awaitable.AwaitStreamOneToOneStep", "public interface AwaitStreamOneToOneStep<I,O> { io.smallrye.mutiny.Multi<O> applyAwaitPerItem(io.smallrye.mutiny.Multi<I> input); }");
        stub(root, "org.pipelineframework.cache.CacheReadBypass", "public interface CacheReadBypass {}");
        stub(root, "org.pipelineframework.cache.CacheKeyTarget", "public interface CacheKeyTarget { Class<?> cacheKeyTargetType(); }");
        stub(root, "org.pipelineframework.step.ConfigurableStep", "public abstract class ConfigurableStep {}");
        stub(root, "org.pipelineframework.step.StepOneToOne", "public interface StepOneToOne<I,O> { io.smallrye.mutiny.Uni<O> applyOneToOne(I input); }");
        stub(root, "org.pipelineframework.step.StepOneToMany", "public interface StepOneToMany<I,O> { io.smallrye.mutiny.Multi<O> applyOneToMany(I input); }");
        stub(root, "org.pipelineframework.step.StepManyToOne", "public interface StepManyToOne<I,O> { io.smallrye.mutiny.Uni<O> applyReduce(io.smallrye.mutiny.Multi<I> inputs); }");
        stub(root, "org.pipelineframework.step.StepManyToMany", "public interface StepManyToMany<I,O> { io.smallrye.mutiny.Multi<O> applyTransform(io.smallrye.mutiny.Multi<I> inputs); }");
        stub(root, "org.pipelineframework.aws.durable.AwsDurableInputDecoder", "public interface AwsDurableInputDecoder { Object decode(String inputJson); }");
        stub(root, "org.pipelineframework.paging.PagedSourceRequest", "public record PagedSourceRequest<I>(I input, String sourceIdentity, java.util.Optional<String> checkpoint, int maxRecords) {}");
        stub(root, "org.pipelineframework.paging.RemotePageRequest", "public record RemotePageRequest<I>(I input, String sourceIdentity, java.util.Optional<String> checkpoint, int maxRecords, String pipelineId, String contractVersion, String releaseVersion, String catalogFingerprint) {}");
        stub(root, "org.pipelineframework.paging.RemotePageWireFrame", "public record RemotePageWireFrame<O>(java.util.Optional<O> item, java.util.Optional<String> completion) {}");
        stub(root, "org.pipelineframework.paging.PagedSourceStream", "public record PagedSourceStream<O>(java.util.concurrent.Flow.Publisher<O> items, java.util.concurrent.CompletionStage<org.pipelineframework.paging.PagedSourceCompletion> completion) {}");
        stub(root, "org.pipelineframework.paging.PagedSourceOperation", "public interface PagedSourceOperation<I,O> { PagedSourceStream<O> openPage(PagedSourceRequest<I> request); }");
        stub(root, "org.pipelineframework.paging.RemotePageFrame", "public interface RemotePageFrame<O> { record Item<O>(O value) implements RemotePageFrame<O> {} record Completion<O>(String value) implements RemotePageFrame<O> {} }");
        stub(root, "org.pipelineframework.paging.RemotePagedSourceBridge", "public class RemotePagedSourceBridge { public String catalogFingerprint() { return \"catalog\"; } public <I,O,F> PagedSourceStream<O> open(PagedSourceRequest<I> request, java.util.concurrent.Flow.Publisher<F> frames, java.util.function.Function<F,RemotePageFrame<O>> decode) { return new PagedSourceStream<>(io.smallrye.mutiny.Multi.createFrom().publisher(frames).onItem().transform(decode).select().where(frame -> frame instanceof RemotePageFrame.Item).onItem().transform(frame -> ((RemotePageFrame.Item<O>) frame).value()), java.util.concurrent.CompletableFuture.completedFuture(null)); } }");
        stub(root, "org.pipelineframework.execution.PipelineExecutionContextHolder", "public class PipelineExecutionContextHolder { public record Identity(String pipelineId, String contractVersion, String releaseVersion) {} public static java.util.Optional<Identity> get() { return java.util.Optional.of(new Identity(\"pipeline\", \"contract\", \"release\")); } }");
    }
}
