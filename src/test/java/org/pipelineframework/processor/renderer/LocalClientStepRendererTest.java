package org.pipelineframework.processor.renderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.ExecutionMode;
import org.pipelineframework.processor.ir.LocalBinding;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.ServiceApiKind;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.ir.TypeMapping;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LocalClientStepRendererTest {

    @TempDir
    Path tempDir;

    @Test
    void forwardsPageOpenThroughLocalSourceClient() throws Exception {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName("ProcessCsvPaymentsInputService")
            .generatedName("ProcessCsvPaymentsInput")
            .servicePackage("org.pipelineframework.csv.service")
            .serviceClassName(ClassName.get("org.pipelineframework.csv.service", "ProcessCsvPaymentsInputService"))
            .streamingShape(StreamingShape.UNARY_STREAMING)
            .executionMode(ExecutionMode.DEFAULT)
            .serviceApiKind(ServiceApiKind.BLOCKING_ITERATOR)
            .inputMapping(new TypeMapping(ClassName.get("org.pipelineframework.csv.domain", "CsvPaymentsInputFile"), null, false))
            .outputMapping(new TypeMapping(ClassName.get("org.pipelineframework.csv.domain", "PaymentRecord"), null, false))
            .enabledTargets(Set.of())
            .build();

        new LocalClientStepRenderer().render(new LocalBinding(model),
            Jsr269GenerationContext.create(null, tempDir, DeploymentRole.ORCHESTRATOR_CLIENT, Set.of(), null, null));

        String source = Files.readString(tempDir.resolve(
            "org/pipelineframework/csv/service/pipeline/ProcessCsvPaymentsInputLocalClientStep.java"));
        assertTrue(source.contains("PagedSourceOperation<CsvPaymentsInputFile, PaymentRecord>"));
        assertTrue(source.contains("PagedSourceOperation.class.cast(this.service)).openPage(request)"));
        assertTrue(source.contains("this.service.process(input)"));
    }

    @Test
    void generatedPagedAdaptersCompileWithFinalServiceWithoutPaging() throws Exception {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName("FinalSource")
            .generatedName("FinalSource")
            .servicePackage("com.example.source")
            .serviceClassName(ClassName.get("com.example.source", "FinalSource"))
            .streamingShape(StreamingShape.UNARY_STREAMING)
            .executionMode(ExecutionMode.DEFAULT)
            .serviceApiKind(ServiceApiKind.BLOCKING_ITERATOR)
            .inputMapping(new TypeMapping(ClassName.get("com.example.source", "Input"), null, false))
            .outputMapping(new TypeMapping(ClassName.get("com.example.source", "Output"), null, false))
            .enabledTargets(Set.of())
            .build();
        var environment = mock(ProcessingEnvironment.class);
        new LocalClientStepRenderer().render(new LocalBinding(model),
            Jsr269GenerationContext.create(environment, tempDir, DeploymentRole.ORCHESTRATOR_CLIENT,
                Set.of(), null, null));
        new BlockingReactiveBridgeRenderer().render(model,
            Jsr269GenerationContext.create(environment, tempDir, DeploymentRole.PIPELINE_SERVER,
                Set.of(), null, null));

        Path sourceDir = tempDir.resolve("com/example/source");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("Input.java"), "package com.example.source; public record Input() {}");
        Files.writeString(sourceDir.resolve("Output.java"), "package com.example.source; public record Output() {}");
        Files.writeString(sourceDir.resolve("FinalSource.java"), """
            package com.example.source;
            public final class FinalSource {
                public io.smallrye.mutiny.Multi<Output> process(Input input) {
                    return io.smallrye.mutiny.Multi.createFrom().empty();
                }
                public java.util.Iterator<Output> iterateBlocking(Input input) {
                    return java.util.Collections.emptyIterator();
                }
            }
            """);
        Map<String, String> compileOnlyStubs = Map.of(
            "io/quarkus/arc/Unremovable.java",
                "package io.quarkus.arc; public @interface Unremovable {}",
            "jakarta/enterprise/context/Dependent.java",
                "package jakarta.enterprise.context; public @interface Dependent {}",
            "jakarta/enterprise/context/ApplicationScoped.java",
                "package jakarta.enterprise.context; public @interface ApplicationScoped {}",
            "jakarta/inject/Inject.java",
                "package jakarta.inject; public @interface Inject {}",
            "org/pipelineframework/step/ConfigurableStep.java",
                "package org.pipelineframework.step; public class ConfigurableStep {}",
            "org/pipelineframework/step/StepOneToMany.java",
                "package org.pipelineframework.step; public interface StepOneToMany<I,O> { io.smallrye.mutiny.Multi<O> applyOneToMany(I input); }",
            "org/pipelineframework/telemetry/LocalClientTracing.java",
                "package org.pipelineframework.telemetry; public final class LocalClientTracing {"
                    + " public static <T> io.smallrye.mutiny.Multi<T> traceMulti(String service, String method, io.smallrye.mutiny.Multi<T> source) { return source; } }",
            "org/pipelineframework/blocking/BlockingExecutionSupport.java",
                "package org.pipelineframework.blocking; public class BlockingExecutionSupport {"
                    + " public <T> io.smallrye.mutiny.Multi<T> emitIterator(boolean virtual, java.util.function.Supplier<java.util.Iterator<T>> items) {"
                    + " throw new UnsupportedOperationException(); } }"
        );
        for (var stub : compileOnlyStubs.entrySet()) {
            Path file = tempDir.resolve(stub.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, stub.getValue());
        }

        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path classesDir = Files.createDirectories(tempDir.resolve("classes"));
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null);
             var sources = Files.walk(tempDir)) {
            List<Path> javaSources = sources.filter(path -> path.toString().endsWith(".java")).toList();
            var units = manager.getJavaFileObjectsFromPaths(javaSources);
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path"),
                    "-d", classesDir.toString()), null, units).call();
            assertTrue(compiled, () -> "Generated source did not compile: " + diagnostics.getDiagnostics());
        }
    }
}
