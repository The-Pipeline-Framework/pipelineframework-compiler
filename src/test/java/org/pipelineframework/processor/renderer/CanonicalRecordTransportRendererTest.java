package org.pipelineframework.processor.renderer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.squareup.javapoet.ClassName;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineFieldNullability;
import org.pipelineframework.config.template.PipelineFieldPresence;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.config.template.PipelineTemplateTypeModel;
import org.pipelineframework.config.template.PipelineTemplateTypeReference;
import org.pipelineframework.processor.Jsr269CompilerServices;
import org.pipelineframework.processor.PipelineCompilerOptions;
import org.pipelineframework.processor.ir.TypeMapping;

class CanonicalRecordTransportRendererTest {
    @TempDir Path output;

    @Test
    void optionalNestedRecordUsesItsMapperInBothRestDirections() throws Exception {
        var model = new PipelineTemplateTypeModel(Map.of(
            "Child", new PipelineTemplateTypeDefinition.RecordType("Child", List.of(
                new PipelineTemplateTypeDefinition.Field("name", new PipelineTemplateTypeReference.Scalar("string")))),
            "Parent", new PipelineTemplateTypeDefinition.RecordType("Parent", List.of(
                new PipelineTemplateTypeDefinition.Field("child", new PipelineTemplateTypeReference.Named("Child"),
                    false, PipelineFieldPresence.OPTIONAL, PipelineFieldNullability.NON_NULL)))));
        List<JavaFileObject> domainSources = List.of(
            source("Child", "public record Child(String name) {}"),
            source("Parent", "public record Parent(org.pipelineframework.type.CanonicalFieldValue<Child> child) {}"));
        var generated = new AtomicBoolean();
        var processor = new AbstractProcessor() {
            @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
            @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
            @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                if (!round.processingOver() && generated.compareAndSet(false, true)) {
                    try {
                        GenerationContext context = mock(GenerationContext.class);
                        when(context.compilerServices()).thenReturn(Jsr269CompilerServices.from(processingEnv));
                        when(context.compilerOptions()).thenReturn(new PipelineCompilerOptions(Map.of()));
                        when(context.canonicalTypeModel()).thenReturn(Optional.of(model));
                        when(context.pipelineBasePackage()).thenReturn("com.example");
                        when(context.outputDir()).thenReturn(output);
                        var resolver = new CanonicalTransportBindingResolver(context);
                        var binding = resolver.resolve(TypeMapping.canonical(
                            ClassName.get("com.example.domain", "Parent"), "Parent")).orElseThrow();
                        new CanonicalRecordTransportRenderer(context, resolver).ensureRest(binding);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }
                return false;
            }
        };
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path classes = output.resolve("classes");
        Files.createDirectories(classes);
        try (var manager = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT,
            java.nio.charset.StandardCharsets.UTF_8)) {
            var task = compiler.getTask(new StringWriter(), manager, diagnostics,
                List.of("-proc:only", "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString()), List.of(), domainSources);
            task.setProcessors(List.of(processor));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        assertTrue(generated.get());
        Path mapper;
        try (var files = Files.list(output.resolve("com/example/transport/generated"))) {
            mapper = files.filter(path -> path.getFileName().toString().startsWith("Parent_"))
                .filter(path -> path.getFileName().toString().endsWith("RestMapper.java"))
                .findFirst().orElseThrow();
        }
        String source = Files.readString(mapper);
        assertTrue(source.contains("childMapper::fromExternal"));
        assertTrue(source.contains("childMapper::toExternal"));
        try (var manager = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT,
            java.nio.charset.StandardCharsets.UTF_8)) {
            List<Path> generatedSources;
            try (var files = Files.walk(output.resolve("com/example"))) {
                generatedSources = files.filter(path -> path.toString().endsWith(".java")).toList();
            }
            var sources = new java.util.ArrayList<JavaFileObject>(domainSources);
            manager.getJavaFileObjectsFromPaths(generatedSources).forEach(sources::add);
            var task = compiler.getTask(new StringWriter(), manager, diagnostics,
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString()), List.of(), sources);
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
    }

    private JavaFileObject source(String name, String body) {
        return new SimpleJavaFileObject(URI.create("string:///com/example/domain/" + name + ".java"),
            JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "package com.example.domain;\n" + body;
            }
        };
    }
}
