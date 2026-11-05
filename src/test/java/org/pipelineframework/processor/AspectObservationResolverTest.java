package org.pipelineframework.processor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.processor.ir.*;
import static org.junit.jupiter.api.Assertions.*;

class AspectObservationResolverTest {
    @TempDir Path directory;

    @Test
    void explainsWhenAnObservedLeafRequiresNestedUnionProjection() throws Exception {
        var file = Files.writeString(directory.resolve("pipeline.yaml"), """
            version: 3
            appName: Nested observer
            basePackage: proof
            transport: LOCAL
            types:
              Review: { java: proof.Types.Review, fields: [[value, string]] }
              Inner: { java: proof.Types.Inner, variants: { review: Review } }
              Outer: { java: proof.Types.Outer, variants: { nested: Inner } }
            steps: []
            """);
        var context = new PipelineCompilationContext(
            org.mockito.Mockito.mock(javax.annotation.processing.ProcessingEnvironment.class), Jsr269SourceInventory.empty());
        context.setPipelineTemplateConfig(new PipelineTemplateConfigLoader().load(file));
        context.setTransportMode(PipelineTransport.LOCAL);
        var mapping = TypeMapping.canonical(ClassName.get("proof", "Types", "Outer"), "Outer");
        var model = new PipelineStepModel.Builder().serviceName("Evaluate").generatedName("Evaluate")
            .servicePackage("proof").serviceClassName(ClassName.get("proof", "Evaluate"))
            .inputMapping(mapping).outputMapping(mapping).streamingShape(StreamingShape.UNARY_UNARY)
            .executionMode(ExecutionMode.DEFAULT).enabledTargets(Set.of()).build();
        var aspect = new PipelineAspectModel("history", AspectScope.GLOBAL, AspectPosition.AFTER_STEP,
            Map.of("targetTypes", java.util.List.of("Review")));
        var failure = assertThrows(IllegalArgumentException.class,
            () -> new AspectObservationResolver(context).resolve(model, aspect));
        assertTrue(failure.getMessage().contains("history"));
        assertTrue(failure.getMessage().contains("Review"));
        assertTrue(failure.getMessage().contains("nested inside union 'Outer'"));
    }
}
