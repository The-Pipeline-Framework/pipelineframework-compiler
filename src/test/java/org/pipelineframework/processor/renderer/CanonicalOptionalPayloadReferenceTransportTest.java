package org.pipelineframework.processor.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.squareup.javapoet.ClassName;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineFieldNullability;
import org.pipelineframework.config.template.PipelineFieldPresence;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.config.template.PipelineTemplateTypeModel;
import org.pipelineframework.config.template.PipelineTemplateTypeReference;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.ir.TypeMapping;
import org.pipelineframework.repository.PayloadReference;
import org.pipelineframework.type.CanonicalFieldValue;

/** The REST and gRPC record mappers must retain canonical optional-field presence. */
class CanonicalOptionalPayloadReferenceTransportTest {
    @TempDir Path directory;

    @Test
    void generatedMappersCompileAndRoundTripAbsentAndPresentPayloadReference() throws Exception {
        Path sources = directory.resolve("sources");
        Path generated = directory.resolve("generated");
        Path classes = directory.resolve("classes");
        Files.createDirectories(generated);
        Files.createDirectories(classes);
        source(sources, "example/domain/InputFile.java", """
            package example.domain;
            public record InputFile(String id,
                org.pipelineframework.type.CanonicalFieldValue<org.pipelineframework.repository.PayloadReference> contentRef,
                org.pipelineframework.type.CanonicalFieldValue<Boolean> retry) { }
            """);
        source(sources, "example/grpc/PipelineTypes.java", """
            package example.grpc;
            public final class PipelineTypes {
                public static final class PayloadReference {
                    private final byte[] bytes;
                    private PayloadReference(byte[] bytes) { this.bytes = bytes; }
                    public static PayloadReference parseFrom(byte[] bytes)
                        throws com.google.protobuf.InvalidProtocolBufferException {
                        return new PayloadReference(bytes);
                    }
                    public byte[] toByteArray() { return bytes; }
                }
                public static final class InputFile {
                    private final String id;
                    private final PayloadReference contentRef;
                    private final Boolean retry;
                    private InputFile(String id, PayloadReference contentRef, Boolean retry) {
                        this.id = id;
                        this.contentRef = contentRef;
                        this.retry = retry;
                    }
                    public String getId() { return id; }
                    public boolean hasContentRef() { return contentRef != null; }
                    public PayloadReference getContentRef() { return contentRef; }
                    public boolean hasRetry() { return retry != null; }
                    public boolean getRetry() { return Boolean.TRUE.equals(retry); }
                    public static Builder newBuilder() { return new Builder(); }
                    public static final class Builder {
                        private String id;
                        private PayloadReference contentRef;
                        private Boolean retry;
                        public Builder setId(String value) { id = value; return this; }
                        public Builder setContentRef(PayloadReference value) { contentRef = value; return this; }
                        public Builder setRetry(boolean value) { retry = value; return this; }
                        public InputFile build() { return new InputFile(id, contentRef, retry); }
                    }
                }
            }
            """);
        source(sources, "org/pipelineframework/proto/PayloadReferenceProtobufCodec.java", """
            package org.pipelineframework.proto;
            // Signature-only fixture: the real codec is covered by its owning module.
            public final class PayloadReferenceProtobufCodec {
                private static final java.util.Map<String, org.pipelineframework.repository.PayloadReference> REFERENCES
                    = new java.util.concurrent.ConcurrentHashMap<>();
                public static byte[] encode(org.pipelineframework.repository.PayloadReference value) {
                    String key = value.key();
                    REFERENCES.put(key, value);
                    return key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                public static org.pipelineframework.repository.PayloadReference decode(byte[] bytes) {
                    return REFERENCES.get(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            """);
        var field = new PipelineTemplateTypeDefinition.Field("contentRef",
            new PipelineTemplateTypeReference.Scalar("payload_ref"), false,
            PipelineFieldPresence.OPTIONAL, PipelineFieldNullability.NON_NULL);
        var retry = new PipelineTemplateTypeDefinition.Field("retry",
            new PipelineTemplateTypeReference.Scalar("bool"), false,
            PipelineFieldPresence.OPTIONAL, PipelineFieldNullability.NON_NULL);
        var model = new PipelineTemplateTypeModel(Map.of("InputFile",
            new PipelineTemplateTypeDefinition.RecordType("InputFile", List.of(
                new PipelineTemplateTypeDefinition.Field("id", new PipelineTemplateTypeReference.Scalar("string")),
                field, retry))));
        var binding = new AtomicReference<CanonicalTransportTypeBinding>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null);
             var paths = Files.walk(sources)) {
            var task = compiler.getTask(null, files, diagnostics,
                List.of("-proc:only", "-s", generated.toString(), "-d", classes.toString(),
                    "-classpath", System.getProperty("java.class.path")), null,
                files.getJavaFileObjectsFromPaths(paths.filter(p -> p.toString().endsWith(".java")).toList()));
            task.setProcessors(List.of(new AbstractProcessor() {
                private boolean rendered;
                @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
                @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_21; }
                @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                    if (rendered || round.processingOver()) return false;
                    rendered = true;
                    try {
                        var context = Jsr269GenerationContext.create(processingEnv, generated,
                            DeploymentRole.ORCHESTRATOR_CLIENT, Set.of(), null, null,
                            PipelineTransport.REST, "example", null, true, Optional.of(model));
                        var resolver = new CanonicalTransportBindingResolver(context);
                        var resolved = resolver.resolve(TypeMapping.canonical(
                            ClassName.get("example.domain", "InputFile"), "InputFile"), PipelineTransport.REST)
                            .orElseThrow();
                        binding.set(resolved);
                        var renderer = new CanonicalRecordTransportRenderer(context, resolver);
                        renderer.ensureRest(resolved);
                        renderer.ensureGrpc(resolved);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    return false;
                }
            }));
            assertTrue(Boolean.TRUE.equals(task.call()), diagnostics.getDiagnostics().toString());
        }
        try (var files = compiler.getStandardFileManager(diagnostics, null, null);
             var authored = Files.walk(sources);
             var emitted = Files.walk(generated)) {
            var all = new java.util.ArrayList<Path>();
            all.addAll(authored.filter(p -> p.toString().endsWith(".java")).toList());
            all.addAll(emitted.filter(p -> p.toString().endsWith(".java")).toList());
            assertTrue(Boolean.TRUE.equals(compiler.getTask(null, files, diagnostics,
                List.of("-proc:none", "-d", classes.toString(), "-classpath", System.getProperty("java.class.path")),
                null, files.getJavaFileObjectsFromPaths(all)).call()), diagnostics.getDiagnostics().toString());
        }
        try (var loader = new URLClassLoader(new java.net.URL[] { classes.toUri().toURL() }, getClass().getClassLoader())) {
            Class<?> domain = loader.loadClass("example.domain.InputFile");
            Class<?> dto = loader.loadClass("example.dto.InputFileDto");
            Class<?> proto = loader.loadClass("example.grpc.PipelineTypes$InputFile");
            Object rest = loader.loadClass(binding.get().restMapperType().canonicalName()).getConstructor().newInstance();
            Class<?> grpc = loader.loadClass(binding.get().grpcMapperType().canonicalName());
            var reference = new PayloadReference("test", "bucket", "object.csv", "text/csv", null,
                null, 7L, null, Map.of(), Optional.empty());
            var json = new ObjectMapper().registerModule(new Jdk8Module());
            for (Boolean retryValue : new Boolean[] { null, Boolean.TRUE, Boolean.FALSE }) {
                PayloadReference value = retryValue == null ? null : reference;
                Object external = dto.getConstructor(String.class, Optional.class, Optional.class)
                    .newInstance("file", Optional.ofNullable(value), Optional.ofNullable(retryValue));
                Object restored = rest.getClass().getMethod("fromExternal", dto).invoke(rest, external);
                CanonicalFieldValue<?> fieldValue = (CanonicalFieldValue<?>) domain.getMethod("contentRef").invoke(restored);
                assertEquals(value == null, fieldValue.isAbsent());
                assertEquals(Optional.ofNullable(value), fieldValue.asOptional());
                CanonicalFieldValue<?> retryField = (CanonicalFieldValue<?>) domain.getMethod("retry").invoke(restored);
                assertEquals(value == null, retryField.isAbsent());
                assertEquals(Optional.ofNullable(retryValue), retryField.asOptional());
                Object returned = rest.getClass().getMethod("toExternal", domain).invoke(rest, restored);
                assertEquals(Optional.ofNullable(value), dto.getMethod("contentRef").invoke(returned));
                assertEquals(Optional.ofNullable(retryValue), dto.getMethod("retry").invoke(returned));
                String encodedJson = json.writeValueAsString(returned);
                assertEquals(value != null, encodedJson.contains("\"contentRef\""));
                assertEquals(value != null, encodedJson.contains("\"retry\""));
                Object decodedJson = json.readValue(encodedJson, dto);
                assertEquals(Optional.ofNullable(value), dto.getMethod("contentRef").invoke(decodedJson));
                assertEquals(Optional.ofNullable(retryValue), dto.getMethod("retry").invoke(decodedJson));

                Object encoded = grpc.getMethod("toProto", domain).invoke(null, restored);
                assertEquals(value != null, proto.getMethod("hasContentRef").invoke(encoded));
                assertEquals(value != null, proto.getMethod("hasRetry").invoke(encoded));
                Object decoded = grpc.getMethod("fromProto", proto).invoke(null, encoded);
                CanonicalFieldValue<?> decodedValue = (CanonicalFieldValue<?>) domain.getMethod("contentRef").invoke(decoded);
                assertEquals(value == null, decodedValue.isAbsent());
                assertEquals(Optional.ofNullable(value), decodedValue.asOptional());
                CanonicalFieldValue<?> decodedRetry = (CanonicalFieldValue<?>) domain.getMethod("retry").invoke(decoded);
                assertEquals(value == null, decodedRetry.isAbsent());
                assertEquals(Optional.ofNullable(retryValue), decodedRetry.asOptional());
            }
            Object explicitNull = json.readValue("{\"id\":\"file\",\"contentRef\":null}", dto);
            Object explicitNullDomain = rest.getClass().getMethod("fromExternal", dto).invoke(rest, explicitNull);
            assertTrue(((CanonicalFieldValue<?>) domain.getMethod("contentRef").invoke(explicitNullDomain)).isAbsent());
            assertFalse(Files.readString(generated.resolve("example/dto/InputFileDto.java"))
                .contains("CanonicalFieldValue"), "REST DTO keeps its simple wire type");
        }
    }

    private void source(Path root, String relative, String text) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}
