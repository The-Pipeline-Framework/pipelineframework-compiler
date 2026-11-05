package org.pipelineframework.processor.awaitable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.processor.parser.StepDefinitionParser;
import static org.junit.jupiter.api.Assertions.*;

class NestedAwaitValidatorTest {
    @TempDir Path directory;

    @Test
    void checksEveryCallerOfASuspendingDescendant() throws Exception {
        String yaml = """
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
        validate(yaml);
        for (String invalid : Set.of(yaml.replace("LOCAL", "REST"),
            yaml.replace("pipeline: middle, cardinality: ONE_TO_ONE", "pipeline: middle, cardinality: ONE_TO_MANY"),
            yaml.replace("pipeline: leaf, cardinality: ONE_TO_ONE", "pipeline: leaf, cardinality: MANY_TO_ONE"))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> validate(invalid));
            assertTrue(failure.getMessage().contains("LOCAL ONE_TO_ONE"), failure.getMessage());
        }
    }

    private void validate(String yaml) throws Exception {
        var file = Files.writeString(directory.resolve("pipeline.yaml"), yaml);
        var config = new PipelineTemplateConfigLoader().load(file);
        new NestedAwaitValidator().validate(config, new StepDefinitionParser().parseDefinitionCatalog(file));
    }
}
