package org.pipelineframework.processor.renderer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.squareup.javapoet.ClassName;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.*;
import org.pipelineframework.processor.Jsr269CompilerServices;
import org.pipelineframework.processor.PipelineCompilerOptions;
import org.pipelineframework.processor.ir.TypeMapping;
import org.pipelineframework.type.CanonicalFieldValue;

/** Exercises emitted DTOs and mappers with an ordinary REST/Jackson mapper. */
class CanonicalRestPresenceMatrixTest {
    @TempDir Path output;

    @Test void generatedRestBoundaryPreservesDocumentedStatesAndZero() throws Exception {
        var definitions = new LinkedHashMap<String, PipelineTemplateTypeDefinition>();
        var authored = new ArrayList<JavaFileObject>();
        for (boolean optional : new boolean[]{false, true}) {
            for (boolean nullable : new boolean[]{false, true}) {
                String name = name(optional, nullable);
                definitions.put(name, new PipelineTemplateTypeDefinition.RecordType(name, List.of(
                    new PipelineTemplateTypeDefinition.Field("value", new PipelineTemplateTypeReference.Scalar("int32"),
                        false, optional ? PipelineFieldPresence.OPTIONAL : PipelineFieldPresence.REQUIRED,
                        nullable ? PipelineFieldNullability.NULLABLE : PipelineFieldNullability.NON_NULL))));
                authored.add(source(name, "public record " + name + "("
                    + (optional || nullable ? "org.pipelineframework.type.CanonicalFieldValue<Integer>" : "Integer")
                    + " value) {}"));
            }
        }
        definitions.put("Repeated", new PipelineTemplateTypeDefinition.RecordType("Repeated", List.of(
            new PipelineTemplateTypeDefinition.Field("values", new PipelineTemplateTypeReference.Scalar("int32"), true))));
        authored.add(source("Repeated", "public record Repeated(java.util.List<Integer> values) {}"));
        definitions.put("Child", new PipelineTemplateTypeDefinition.RecordType("Child", List.of(
            new PipelineTemplateTypeDefinition.Field("name", new PipelineTemplateTypeReference.Scalar("string")))));
        authored.add(source("Child", "public record Child(String name) {}"));
        definitions.put("Nested", new PipelineTemplateTypeDefinition.RecordType("Nested", List.of(
            new PipelineTemplateTypeDefinition.Field("value", new PipelineTemplateTypeReference.Named("Child"), false,
                PipelineFieldPresence.OPTIONAL, PipelineFieldNullability.NULLABLE))));
        authored.add(source("Nested", "public record Nested(org.pipelineframework.type.CanonicalFieldValue<Child> value) {}"));
        Map<String, CanonicalTransportTypeBinding> bindings = new LinkedHashMap<>();
        Path classes = compile(new PipelineTemplateTypeModel(definitions), authored, bindings);
        ObjectMapper json = new ObjectMapper().registerModule(new Jdk8Module());
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            for (boolean optional : new boolean[]{false, true}) {
                for (boolean nullable : new boolean[]{false, true}) {
                    String name = name(optional, nullable);
                    var binding = bindings.get(name);
                    Class<?> dto = loader.loadClass(binding.restDtoType().canonicalName());
                    Class<?> domain = loader.loadClass(binding.javaType().canonicalName());
                    Object mapper = loader.loadClass(binding.restMapperType().canonicalName()).getConstructor().newInstance();
                    for (String input : List.of("{}", "{\"value\":null}", "{\"value\":0}", "{\"value\":7}")) {
                        boolean absent = input.equals("{}");
                        boolean explicitNull = input.contains("null");
                        if ((absent && !optional) || (explicitNull && !nullable)) {
                            assertThrows(JsonMappingException.class, () -> json.readValue(input, dto), name + " " + input);
                            continue;
                        }
                        Object external = json.readValue(input, dto);
                        Object value = mapper.getClass().getMethod("fromExternal", dto).invoke(mapper, external);
                        if (optional || nullable) {
                            var state = (CanonicalFieldValue<?>) domain.getMethod("value").invoke(value);
                            assertEquals(absent, state.isAbsent(), name + " " + input);
                            assertEquals(explicitNull, state.isNull(), name + " " + input);
                        }
                        Object returned = mapper.getClass().getMethod("toExternal", domain).invoke(mapper, value);
                        String wire = json.writeValueAsString(returned);
                        assertEquals(json.readTree(input), json.readTree(wire), name + " " + input);
                        Object decoded = mapper.getClass().getMethod("fromExternal", dto)
                            .invoke(mapper, json.readValue(wire, dto));
                        assertEquals(value, decoded);
                    }
                }
            }
            Class<?> repeated = loader.loadClass(bindings.get("Repeated").restDtoType().canonicalName());
            for (String input : List.of("{}", "{\"values\":null}")) {
                assertEquals(List.of(), repeated.getMethod("values").invoke(json.readValue(input, repeated)));
            }
            assertEquals(List.of(0, 0, 7), repeated.getMethod("values")
                .invoke(json.readValue("{\"values\":[0,0,7]}", repeated)));
            var nested = bindings.get("Nested");
            Class<?> dto = loader.loadClass(nested.restDtoType().canonicalName());
            Class<?> domain = loader.loadClass(nested.javaType().canonicalName());
            Object mapper = loader.loadClass(nested.restMapperType().canonicalName()).getConstructor().newInstance();
            for (String input : List.of("{}", "{\"value\":null}", "{\"value\":{\"name\":\"child\"}}")) {
                Object value = mapper.getClass().getMethod("fromExternal", dto).invoke(mapper, json.readValue(input, dto));
                Object external = mapper.getClass().getMethod("toExternal", domain).invoke(mapper, value);
                assertEquals(json.readTree(input), json.readTree(json.writeValueAsString(external)));
            }
        }
    }

    private String name(boolean optional, boolean nullable) {
        return (optional ? "Optional" : "Required") + (nullable ? "Nullable" : "NonNull");
    }

    private Path compile(PipelineTemplateTypeModel model, List<JavaFileObject> authored,
            Map<String, CanonicalTransportTypeBinding> bindings) throws Exception {
        var emitted = new AtomicBoolean();
        var processor = new AbstractProcessor() {
            @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
            @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
            @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                if (round.processingOver() || !emitted.compareAndSet(false, true)) return false;
                try {
                    var context = mock(GenerationContext.class);
                    when(context.compilerServices()).thenReturn(Jsr269CompilerServices.from(processingEnv));
                    when(context.compilerOptions()).thenReturn(new PipelineCompilerOptions(Map.of()));
                    when(context.canonicalTypeModel()).thenReturn(Optional.of(model));
                    when(context.pipelineBasePackage()).thenReturn("presence");
                    when(context.outputDir()).thenReturn(output);
                    var resolver = new CanonicalTransportBindingResolver(context);
                    for (String name : model.definitions().keySet()) {
                        var binding = resolver.resolve(TypeMapping.canonical(ClassName.get("presence.domain", name), name)).orElseThrow();
                        bindings.put(name, binding);
                        var renderer = new CanonicalRecordTransportRenderer(context, resolver);
                        renderer.ensureRest(binding);
                        if (name.equals("RequiredNullable") || name.equals("OptionalNullable") || name.equals("Nested")) {
                            var failure = assertThrows(IllegalStateException.class, () -> renderer.ensureGrpc(binding));
                            assertTrue(failure.getMessage().contains("nullable fields is not supported"));
                        }
                    }
                } catch (Exception failure) { throw new IllegalStateException(failure); }
                return false;
            }
        };
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path classes = output.resolve("classes");
        Files.createDirectories(classes);
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = compiler.getTask(new StringWriter(), manager, diagnostics,
                List.of("-proc:only", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                List.of(), authored);
            task.setProcessors(List.of(processor));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
            var all = new ArrayList<JavaFileObject>(authored);
            try (var files = Files.walk(output.resolve("presence"))) {
                manager.getJavaFileObjectsFromPaths(files.filter(p -> p.toString().endsWith(".java")).toList()).forEach(all::add);
            }
            assertTrue(compiler.getTask(new StringWriter(), manager, diagnostics,
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                List.of(), all).call(), diagnostics.getDiagnostics().toString());
        }
        return classes;
    }

    private JavaFileObject source(String name, String body) {
        return new SimpleJavaFileObject(URI.create("string:///presence/domain/" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreErrors) { return "package presence.domain;\n" + body; }
        };
    }
}
