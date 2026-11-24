package org.pipelineframework.processor.awaitable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.processor.mapping.PipelinePlacementModel;
import org.pipelineframework.processor.mapping.PipelinePlacementModel.InvocationLocality;
import org.pipelineframework.processor.mapping.PipelinePlacementModel.StepPlacement;
import org.pipelineframework.processor.parser.StepDefinitionParser;
import static org.junit.jupiter.api.Assertions.*;

class NestedAwaitValidatorTest {
    @TempDir Path directory;

    private static final String NESTED_YAML = """
        version: 3
        appName: Nested
        basePackage: example
        transport: LOCAL
        types:
          Request: { fields: [[id, string]] }
          Result: { fields: [[id, string]] }
        contract: { input: Request, output: Result }
        pipelines:
          leaf:
            input: Request
            output: Result
            steps:
              - name: Ask
                service: example.Ask
                cardinality: ONE_TO_ONE
                input: Request
                output: Result
                java: { input: example.Request, output: example.Result }
                await:
                  operationOutput: { type: Request, java: example.Request }
                  timeout: PT1M
                  correlation: { strategy: interactionId }
                  transport: { type: interaction-api }
          middle:
            input: Request
            output: Result
            steps:
              - { name: Ask child, pipeline: leaf, cardinality: ONE_TO_ONE, input: Request, output: Result, java: { input: example.Request, output: example.Result } }
        steps:
          - { name: Negotiate, pipeline: middle, cardinality: ONE_TO_ONE, input: Request, output: Result, java: { input: example.Request, output: example.Result } }
        """;

    @Test
    void checksEveryCallerOfASuspendingDescendant() throws Exception {
        validate(NESTED_YAML);
        for (String invalid : Set.of(NESTED_YAML.replace("LOCAL", "REST"),
            NESTED_YAML.replace("pipeline: middle, cardinality: ONE_TO_ONE", "pipeline: middle, cardinality: ONE_TO_MANY"),
            NESTED_YAML.replace("pipeline: leaf, cardinality: ONE_TO_ONE", "pipeline: leaf, cardinality: MANY_TO_ONE"))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> validate(invalid));
            assertTrue(failure.getMessage().contains("LOCAL ONE_TO_ONE"), failure.getMessage());
        }
    }

    @Test
    void admitsRestFunctionNestedAwaitWhenPlacementIsColocatedAndRejectsRemoteBoundary() throws Exception {
        String restFunctionYaml = NESTED_YAML.replace("transport: LOCAL", "transport: REST\nplatform: FUNCTION");
        var file = Files.writeString(directory.resolve("pipeline.yaml"), restFunctionYaml);
        var config = new PipelineTemplateConfigLoader().load(file);
        var parsed = new StepDefinitionParser().parseDefinitionCatalog(file);

        var colocated = new PipelinePlacementModel(
            Map.of(),
            Map.of(
                "$root", new StepPlacement("app", "application", InvocationLocality.LOCAL_IN_SEGMENT),
                "middle", new StepPlacement("app", "application", InvocationLocality.LOCAL_IN_SEGMENT),
                "leaf", new StepPlacement("app", "application", InvocationLocality.LOCAL_IN_SEGMENT)),
            List.of(),
            "app",
            "application",
            true);
        assertDoesNotThrow(() -> new NestedAwaitValidator().validate(config, parsed, colocated));

        var split = new PipelinePlacementModel(
            Map.of(),
            Map.of(
                "$root", new StepPlacement("app", "application", InvocationLocality.LOCAL_IN_SEGMENT),
                "middle", new StepPlacement("app", "application", InvocationLocality.LOCAL_IN_SEGMENT),
                "leaf", new StepPlacement("remote-svc", "remote", InvocationLocality.REMOTE_BOUNDARY)),
            List.of(),
            "app",
            "application",
            false);
        var failure = assertThrows(IllegalArgumentException.class,
            () -> new NestedAwaitValidator().validate(config, parsed, split));
        assertTrue(failure.getMessage().contains("leaf"), failure.getMessage());
        assertTrue(failure.getMessage().contains("REMOTE_BOUNDARY"), failure.getMessage());

        // Non-ONE_TO_ONE remains rejected even when placement is colocated.
        var nonUnaryFile = Files.writeString(directory.resolve("pipeline-non-unary.yaml"),
            restFunctionYaml.replace("pipeline: leaf, cardinality: ONE_TO_ONE", "pipeline: leaf, cardinality: MANY_TO_ONE"));
        var nonUnaryConfig = new PipelineTemplateConfigLoader().load(nonUnaryFile);
        var nonUnaryParsed = new StepDefinitionParser().parseDefinitionCatalog(nonUnaryFile);
        assertThrows(IllegalArgumentException.class,
            () -> new NestedAwaitValidator().validate(nonUnaryConfig, nonUnaryParsed, colocated));
    }

    private void validate(String yaml) throws Exception {
        var file = Files.writeString(directory.resolve("pipeline.yaml"), yaml);
        var config = new PipelineTemplateConfigLoader().load(file);
        new NestedAwaitValidator().validate(config, new StepDefinitionParser().parseDefinitionCatalog(file));
    }
}
