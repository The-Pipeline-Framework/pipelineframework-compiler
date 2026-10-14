package org.pipelineframework.processor.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.javapoet.ClassName;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.config.template.PipelineTemplateTypeReference;

class CanonicalUnionRestRendererTest {
    @TempDir Path directory;

    @Test
    void rejectsVariantThatCollidesWithReservedWireDiscriminator() {
        GenerationContext context = mock(GenerationContext.class);
        var union = new PipelineTemplateTypeDefinition.UnionType("Outcome", Map.of(
            "discriminator", new PipelineTemplateTypeDefinition.Variant(
                "discriminator", new PipelineTemplateTypeReference.Named("Approved"))));
        var binding = binding("Outcome", union);
        var failure = assertThrows(IllegalStateException.class,
            () -> new CanonicalUnionRestRenderer(context, null).ensureRest(binding, union));
        assertTrue(failure.getMessage().contains("reserved wire property 'discriminator'"));
    }

    @Test
    void rejectsBlankDiscriminatorBeforeConstructingJavaVariantName() {
        GenerationContext context = mock(GenerationContext.class);
        var union = new PipelineTemplateTypeDefinition.UnionType("Outcome", Map.of(
            "", new PipelineTemplateTypeDefinition.Variant("", new PipelineTemplateTypeReference.Named("Approved"))));
        var failure = assertThrows(IllegalStateException.class,
            () -> new CanonicalUnionRestRenderer(context, null).ensureRest(binding("Outcome", union), union));
        assertTrue(failure.getMessage().contains("non-empty variant discriminator"));
    }

    @Test
    void generatedUnionCompilesAndRoundTripsBothTypedVariantsOverJson() throws Exception {
        Path sourceDir = directory.resolve("src");
        Path classesDir = directory.resolve("classes");
        Files.createDirectories(classesDir);
        GenerationContext context = mock(GenerationContext.class);
        when(context.outputDir()).thenReturn(sourceDir);
        var approved = binding("Approved", new PipelineTemplateTypeDefinition.RecordType("Approved", List.of()));
        var rejected = binding("Rejected", new PipelineTemplateTypeDefinition.RecordType("Rejected", List.of()));
        var union = binding("Outcome", new PipelineTemplateTypeDefinition.UnionType("Outcome", Map.of(
            "approved", new PipelineTemplateTypeDefinition.Variant("approved", new PipelineTemplateTypeReference.Named("Approved")),
            "rejected", new PipelineTemplateTypeDefinition.Variant("rejected", new PipelineTemplateTypeReference.Named("Rejected")))));
        new CanonicalUnionRestRenderer(context, null).renderRest(union, List.of(
            new CanonicalUnionRestRenderer.VariantBinding("approved", approved, "payload0"),
            new CanonicalUnionRestRenderer.VariantBinding("rejected", rejected, "payload1")));

        source(sourceDir, "example/domain/Outcome.java", """
            package example.domain;
            public sealed interface Outcome permits Outcome.Approved, Outcome.Rejected {
                record Approved(ApprovedValue value) implements Outcome { }
                record Rejected(RejectedValue value) implements Outcome { }
            }
            """);
        source(sourceDir, "example/domain/Item.java", "package example.domain; public record Item(String id) { }\n");
        source(sourceDir, "example/domain/ApprovedValue.java", "package example.domain; public record ApprovedValue(Item item, String state) { }\n");
        source(sourceDir, "example/domain/RejectedValue.java", "package example.domain; public record RejectedValue(Item item, String reason) { }\n");
        source(sourceDir, "example/dto/ItemDto.java", "package example.dto; public record ItemDto(String id) { }\n");
        source(sourceDir, "example/dto/ApprovedDto.java", "package example.dto; public record ApprovedDto(ItemDto item, String state) { }\n");
        source(sourceDir, "example/dto/RejectedDto.java", "package example.dto; public record RejectedDto(ItemDto item, String reason) { }\n");
        source(sourceDir, "example/transport/ApprovedRestMapper.java", """
            package example.transport;
            public final class ApprovedRestMapper implements org.pipelineframework.mapper.Mapper<example.domain.ApprovedValue, example.dto.ApprovedDto> {
                public example.domain.ApprovedValue fromExternal(example.dto.ApprovedDto value) {
                    return new example.domain.ApprovedValue(new example.domain.Item(value.item().id()), value.state());
                }
                public example.dto.ApprovedDto toExternal(example.domain.ApprovedValue value) {
                    return new example.dto.ApprovedDto(new example.dto.ItemDto(value.item().id()), value.state());
                }
            }
            """);
        source(sourceDir, "example/transport/RejectedRestMapper.java", """
            package example.transport;
            public final class RejectedRestMapper implements org.pipelineframework.mapper.Mapper<example.domain.RejectedValue, example.dto.RejectedDto> {
                public example.domain.RejectedValue fromExternal(example.dto.RejectedDto value) {
                    return new example.domain.RejectedValue(new example.domain.Item(value.item().id()), value.reason());
                }
                public example.dto.RejectedDto toExternal(example.domain.RejectedValue value) {
                    return new example.dto.RejectedDto(new example.dto.ItemDto(value.item().id()), value.reason());
                }
            }
            """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null);
             var paths = Files.walk(sourceDir)) {
            var sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertTrue(compiler.getTask(null, files, diagnostics, List.of("-d", classesDir.toString(),
                "-classpath", System.getProperty("java.class.path")), null,
                files.getJavaFileObjectsFromPaths(sources)).call(), diagnostics.getDiagnostics().toString());
        }
        try (var loader = new URLClassLoader(new java.net.URL[] { classesDir.toUri().toURL() }, getClass().getClassLoader())) {
            var mapper = loader.loadClass("example.transport.OutcomeRestMapper").getConstructor().newInstance();
            var domain = loader.loadClass("example.domain.Outcome");
            var dto = loader.loadClass("example.dto.OutcomeDto");
            var item = loader.loadClass("example.domain.Item").getConstructor(String.class).newInstance("item-7");
            var mapperTo = mapper.getClass().getMethod("toExternal", domain);
            var mapperFrom = mapper.getClass().getMethod("fromExternal", dto);
            var json = new ObjectMapper();
            for (String variant : List.of("Approved", "Rejected")) {
                var valueType = loader.loadClass("example.domain." + variant + "Value");
                var value = valueType.getConstructor(item.getClass(), String.class).newInstance(item, variant);
                var wrapper = loader.loadClass("example.domain.Outcome$" + variant)
                    .getConstructor(valueType).newInstance(value);
                Object external = mapperTo.invoke(mapper, wrapper);
                String encoded = json.writeValueAsString(external);
                assertTrue(encoded.contains("\"discriminator\":\"" + variant.toLowerCase() + "\""));
                assertTrue(encoded.contains("\"id\":\"item-7\""));
                assertEquals(wrapper, mapperFrom.invoke(mapper, json.readValue(encoded, dto)));
            }
            for (String invalid : List.of(
                "{\"discriminator\":null,\"approved\":{\"item\":{\"id\":\"x\"},\"state\":\"ok\"}}",
                "{\"discriminator\":\"unknown\"}",
                "{\"discriminator\":\"approved\"}",
                "{\"discriminator\":\"approved\",\"rejected\":{\"item\":{\"id\":\"x\"},\"reason\":\"no\"}}",
                "{\"discriminator\":\"approved\",\"approved\":{\"item\":{\"id\":\"x\"},\"state\":\"ok\"},\"rejected\":{\"item\":{\"id\":\"x\"},\"reason\":\"no\"}}")) {
                assertThrows(JsonMappingException.class, () -> json.readValue(invalid, dto));
            }
        }
    }

    private CanonicalTransportTypeBinding binding(String name, PipelineTemplateTypeDefinition definition) {
        return new CanonicalTransportTypeBinding(name, definition,
            ClassName.get("example.domain", name.equals("Approved") ? "ApprovedValue" : name.equals("Rejected") ? "RejectedValue" : name),
            ClassName.get("example.dto", name + "Dto"),
            ClassName.get("example.transport", name + "RestMapper"),
            ClassName.get("example.grpc", "PipelineTypes", name),
            ClassName.get("example.transport", name + "GrpcMapper"), Optional.empty());
    }

    private void source(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
