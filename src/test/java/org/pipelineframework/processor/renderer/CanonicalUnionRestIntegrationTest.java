package org.pipelineframework.processor.renderer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.javapoet.ClassName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipelineframework.config.PlatformMode;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.config.template.PipelineTemplateTypeModel;
import org.pipelineframework.config.template.PipelineTemplateTypeReference;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.DeferredCompletionSelection;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

/** Integration of normalized union resolution, JSR-269 record discovery, and emitted Java sources. */
class CanonicalUnionRestIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneToManyOutputAndTerminalInputShareCompilableRestUnionRepresentation(boolean externalPayload)
        throws Exception {
        Path sources = directory.resolve("sources");
        Path generated = directory.resolve("generated");
        Path classes = directory.resolve("classes");
        Files.createDirectories(generated);
        Files.createDirectories(classes);
        source(sources, "example/domain/Item.java", "package example.domain; public record Item(String id) { }\n");
        source(sources, "example/domain/Input.java", "package example.domain; public record Input(String id) { }\n");
        source(sources, "example/domain/Receipt.java", "package example.domain; public record Receipt(String id) { }\n");
        source(sources, externalPayload ? "example/external/Approved.java" : "example/domain/Approved.java",
            externalPayload
                ? "package example.external; public record Approved(example.domain.Item item, String state) { }\n"
                : "package example.domain; public record Approved(Item item, String state) { }\n");
        source(sources, "example/domain/Rejected.java", "package example.domain; public record Rejected(Item item, String reason) { }\n");
        source(sources, "example/domain/Outcome.java", """
            package example.domain;
            public sealed interface Outcome permits Outcome.Approved, Outcome.Rejected {
                String discriminator();
                record Approved(%s.Approved value) implements Outcome {
                    public String discriminator() { return "approved"; }
                }
                record Rejected(example.domain.Rejected value) implements Outcome {
                    public String discriminator() { return "rejected"; }
                }
            }
            """.formatted(externalPayload ? "example.external" : "example.domain"));
        var definitions = new LinkedHashMap<String, PipelineTemplateTypeDefinition>();
        definitions.put("Item", record("Item", field("id", scalar("string"))));
        definitions.put("Input", record("Input", field("id", scalar("string"))));
        definitions.put("Receipt", record("Receipt", field("id", scalar("string"))));
        definitions.put("Approved", record("Approved", field("item", named("Item")), field("state", scalar("string"))));
        definitions.put("Rejected", record("Rejected", field("item", named("Item")), field("reason", scalar("string"))));
        definitions.put("Outcome", new PipelineTemplateTypeDefinition.UnionType("Outcome", Map.of(
            "approved", new PipelineTemplateTypeDefinition.Variant("approved", named("Approved")),
            "rejected", new PipelineTemplateTypeDefinition.Variant("rejected", named("Rejected")))));
        var model = new PipelineTemplateTypeModel(definitions);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null);
             var paths = Files.walk(sources)) {
            var domainSources = paths.filter(path -> path.toString().endsWith(".java")).toList();
            var task = compiler.getTask(null, files, diagnostics,
                List.of("-proc:only", "-s", generated.toString(), "-d", classes.toString(),
                    "-classpath", System.getProperty("java.class.path")), null,
                files.getJavaFileObjectsFromPaths(domainSources));
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
                            PipelineTransport.REST, "example", null, true, Optional.of(model))
                            .withPlatformMode(PlatformMode.FUNCTION);
                        var oneToMany = step("Expand", StreamingShape.UNARY_STREAMING, "Input", "Input")
                            .toBuilder().deferredCompletionSelection(new DeferredCompletionSelection(
                                ClassName.get("example.domain", "Outcome"), "Outcome", Optional.empty(),
                                Duration.ofMinutes(5), List.of("id"), "interactionId", "sqs", Map.of(),
                                Optional.empty(), Optional.empty())).build();
                        var terminal = step("Finalize", StreamingShape.UNARY_UNARY, "Outcome", "Receipt");
                        CanonicalTransportBindingResolver.resolveAndEnsure(context, oneToMany, PipelineTransport.REST);
                        new DeferredCompletionStepRenderer().render(oneToMany, context);
                        if (!Files.exists(generated.resolve("example/dto/OutcomeDto.java"))) {
                            throw new IllegalStateException("Await completion did not emit its distinct union REST DTO");
                        }
                        CanonicalTransportBindingResolver.resolveAndEnsure(context, terminal, PipelineTransport.REST);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    return false;
                }
            }));
            assertTrue(Boolean.TRUE.equals(task.call()), diagnostics.getDiagnostics().toString());
        }
        assertTrue(Files.exists(generated.resolve("example/dto/OutcomeDto.java")));
        String deferred = Files.readString(generated.resolve("example/service/pipeline/ExpandDeferredCompletionStep.java"));
        assertTrue(deferred.contains("OutcomeDto"), deferred);
        assertTrue(!deferred.contains(".fromProto"), deferred);
        try (var paths = Files.walk(generated.resolve("example/transport/generated"))) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().matches("Outcome_[0-9a-f]+RestMapper.java")));
        }
        try (var files = compiler.getStandardFileManager(diagnostics, null, null);
             var authored = Files.walk(sources);
             var emitted = Files.walk(generated)) {
            var all = new java.util.ArrayList<Path>();
            all.addAll(authored.filter(path -> path.toString().endsWith(".java")).toList());
            // Deferred completion sources require runtime APIs; the application reactor compiles that full boundary.
            all.addAll(emitted.filter(path -> path.toString().endsWith(".java")
                && !path.getFileName().toString().endsWith("DeferredCompletionStep.java")).toList());
            assertTrue(Boolean.TRUE.equals(compiler.getTask(null, files, diagnostics,
                List.of("-proc:none", "-d", classes.toString(), "-classpath", System.getProperty("java.class.path")),
                null, files.getJavaFileObjectsFromPaths(all)).call()), diagnostics.getDiagnostics().toString());
        }
    }

    private PipelineStepModel step(String name, StreamingShape shape, String input, String output) {
        return new PipelineStepModel.Builder().serviceName(name + "Service")
            .servicePackage("example.service")
            .serviceClassName(ClassName.get("example.service", name + "Service"))
            .streamingShape(shape).executionMode(ExecutionMode.DEFAULT)
            .inputMapping(TypeMapping.canonical(ClassName.get("example.domain", input), input))
            .outputMapping(TypeMapping.canonical(ClassName.get("example.domain", output), output))
            .build();
    }

    private PipelineTemplateTypeDefinition.RecordType record(String name, PipelineTemplateTypeDefinition.Field... fields) {
        return new PipelineTemplateTypeDefinition.RecordType(name, List.of(fields));
    }

    private PipelineTemplateTypeDefinition.Field field(String name, PipelineTemplateTypeReference type) {
        return new PipelineTemplateTypeDefinition.Field(name, type);
    }

    private PipelineTemplateTypeReference scalar(String name) { return new PipelineTemplateTypeReference.Scalar(name); }
    private PipelineTemplateTypeReference named(String name) { return new PipelineTemplateTypeReference.Named(name); }

    private void source(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
