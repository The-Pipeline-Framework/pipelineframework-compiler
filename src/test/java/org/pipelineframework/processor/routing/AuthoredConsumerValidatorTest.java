package org.pipelineframework.processor.routing;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import static org.junit.jupiter.api.Assertions.*;

class AuthoredConsumerValidatorTest {
    @TempDir Path tempDir;

    @Test
    void rejectsConsumersSeparatedByOtherStepsAndReintroducedTypes() throws Exception {
        var failure = assertThrows(IllegalArgumentException.class, () -> validate("""
              - { name: First, service: example.First, cardinality: ONE_TO_ONE, input: A, output: B }
              - { name: Middle, service: example.Middle, cardinality: ONE_TO_ONE, input: B, output: A }
              - { name: Later, service: example.Later, cardinality: ONE_TO_ONE, input: A, output: Result }
            """, ""));
        assertDiagnostic(failure, "A", "First", "Later");
    }

    @Test
    void resolvesAliasesAndNestedUnionsBeforeComparingConsumers() throws Exception {
        var failure = assertThrows(IllegalArgumentException.class, () -> validate("""
              - { name: Alias consumer, service: example.First, cardinality: ONE_TO_ONE, input: Alias, output: Nested }
              - { name: Union consumer, service: example.Second, cardinality: ONE_TO_ONE, input: Nested, accepts: [Group], output: Result }
            """, ""));
        assertDiagnostic(failure, "A", "Alias consumer", "Union consumer");
        assertDoesNotThrow(() -> validate("""
              - { name: A consumer, service: example.First, cardinality: ONE_TO_ONE, input: Group, accepts: [A], output: Group }
              - { name: B consumer, service: example.Second, cardinality: ONE_TO_ONE, input: Group, accepts: [B], output: Result }
            """, ""));
    }

    @Test
    void checksQueryAndCommandDeclarationsAgainstAwaitDecoratedServices() {
        for (String kind : java.util.List.of("query", "command")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> validate("""
                  - name: Native operation
                    kind: %s
                    using: application
                    operation: observe
                    cardinality: ONE_TO_ONE
                    input: A
                    output: B
                  - { name: Reintroduce A, service: example.Normalize, cardinality: ONE_TO_ONE, input: B, output: A }
                  - name: Await operation
                    service: example.AwaitOperation
                    cardinality: ONE_TO_ONE
                    input: A
                    output: Result
                    await:
                      operationOutput: { type: B }
                      timeout: PT1M
                      correlation: { strategy: interactionId }
                      transport: { type: interaction-api }
                """.formatted(kind), ""));
            assertDiagnostic(failure, "A", "Native operation", "Await operation");
        }
    }

    @Test
    void scopesConsumersToEachDefinitionAndDoesNotExemptAuthoredRecursion() throws Exception {
        String root = "  - { name: Root consumer, service: example.Root, cardinality: ONE_TO_ONE, input: A, output: Result }\n";
        assertDoesNotThrow(() -> validate(root, """
            pipelines:
              first:
                input: A
                output: Result
                steps: [{ name: First consumer, service: example.First, cardinality: ONE_TO_ONE, input: A, output: Result }]
              second:
                input: A
                output: Result
                steps: [{ name: Second consumer, service: example.Second, cardinality: ONE_TO_ONE, input: A, output: Result }]
            """));
        var failure = assertThrows(IllegalArgumentException.class, () -> validate(root, """
            pipelines:
              inner:
                input: A
                output: Result
                steps:
                  - { name: Initial query, service: example.First, cardinality: ONE_TO_ONE, input: A, output: A }
                  - { name: Authored recursion, pipeline: inner, cardinality: ONE_TO_ONE, input: A, output: Result }
            """));
        assertDiagnostic(failure, "A", "Initial query", "Authored recursion");
        assertTrue(failure.getMessage().contains("inner"));
    }

    @Test
    void migratedPadelPotHasOneConsumerPerConcreteType() throws Exception {
        var resource = getClass().getResource("/compatibility/padelpot/pipeline.yaml");
        assertNotNull(resource);
        var config = new PipelineTemplateConfigLoader().load(Path.of(resource.toURI()));
        assertDoesNotThrow(() -> new AuthoredConsumerValidator().validate(config));
        assertEquals(java.util.List.of("TurnState"), config.recur());
        assertEquals(9, config.steps().size());
        assertEquals(java.util.Set.of("TurnState", "AnsweredReview", "Result"),
            config.typeModel().concreteTypeNames("TurnCompletion"));
        assertTrue(config.steps().stream().noneMatch(step -> step.inputTypeName().equals(step.outputTypeName())));
    }

    private void validate(String steps, String definitions) throws Exception {
        var file = tempDir.resolve("pipeline.yaml");
        Files.writeString(file, """
            version: 3
            appName: Consumer validation
            basePackage: example
            transport: LOCAL
            connectors: { application: { provider: fixture, version: 1 } }
            types:
              A: { fields: [[id, string]] }
              B: { fields: [[id, string]] }
              C: { fields: [[id, string]] }
              Result: { fields: [[id, string]] }
              Alias: { alias: A }
              Group: { variants: { a: A, b: B } }
              Nested: { variants: { group: Group, c: C } }
            """ + definitions + "steps:\n" + steps);
        new AuthoredConsumerValidator().validate(new PipelineTemplateConfigLoader().load(file));
    }

    private void assertDiagnostic(Exception failure, String type, String first, String second) {
        assertTrue(failure.getMessage().contains("'" + type + "'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("'" + first + "'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("'" + second + "'"), failure.getMessage());
    }
}
