/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pipelineframework.compiler.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeLocalCompilerTest {
    private static final String FIRST =
        "  - { name: A, cardinality: ONE_TO_ONE, input: InputValue, output: FirstValue }\n";
    private static final String SECOND =
        "  - { name: B, cardinality: ONE_TO_ONE, input: FirstValue, output: SecondValue }\n";
    private static final String THIRD =
        "  - { name: C, cardinality: ONE_TO_ONE, input: SecondValue, output: OutputValue }\n";
    private static final String DSL = """
        version: 3
        appName: node-abc
        basePackage: org.example
        transport: LOCAL
        platform: COMPUTE
        contract: { input: InputValue, output: OutputValue }
        types:
          InputValue: { fields: [[value, int32]] }
          FirstValue: { fields: [[value, int32]] }
          SecondValue: { fields: [[value, int32]] }
          OutputValue: { fields: [[value, int32]] }
        steps:
        """ + FIRST + SECOND + THIRD;
    private static final String A_HANDLER = handler("A", "normalize", "InputValue", "FirstValue", 0);
    private static final String B_HANDLER = handler("B", "apply", "FirstValue", "SecondValue", 1);
    private static final String C_HANDLER = handler("C", "finish", "SecondValue", "OutputValue", 0);
    private static final String BINDINGS = """
        {
          "schemaVersion":1.0,
          "profile":"tpf-polyglot-unary-policy/1",
          "pipelineId":"node-abc",
          "handlers":[%s,%s,%s]
        }
        """.formatted(C_HANDLER, B_HANDLER, A_HANDLER);

    private static String handler(String step, String name, String input, String output, int retries) {
        return """
            {"step":"%s","handler":"%s","inputType":"%s","outputType":"%s","retryLimit":%d}
            """.formatted(step, name, input, output, retries).strip();
    }

    @TempDir
    Path temp;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void generatesDeterministicTypedBindingAndUsesAuthoredOrder() throws IOException, NoSuchAlgorithmException {
        Path output = Files.createDirectory(temp.resolve("generated"));
        generate(DSL, BINDINGS, output);
        try (var entries = Files.list(output)) {
            assertEquals(Set.of("projection.json", "models.ts", "application.ts"),
                entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
        // Exact outputs captured from the Node compiler before extracting the shared projection.
        var baseline = Map.of(
            "application.ts", "122d76593269306237c2666ceb9eed6af81e7f48caeb2d00134623c29136f790",
            "models.ts", "5b406c69931265b405dfc1d21746944d17e6150dd6025d53e0d81677d88d044a",
            "projection.json", "75e250dc340566bfdcaf09097900098eeb8bdc24b625e89b9a46a529c5f11c96");
        for (var entry : baseline.entrySet()) {
            assertEquals(entry.getValue(), sha256(Files.readAllBytes(output.resolve(entry.getKey()))), entry.getKey());
        }
        Path projectionFile = output.resolve("projection.json");
        JsonNode projection = json.readTree(projectionFile.toFile());
        assertEquals("tpf-polyglot-unary-policy/1", projection.at("/profile").textValue());
        assertEquals("compiler-node-local", projection.at("/kind").textValue());
        assertEquals("node-abc", projection.at("/pipeline/id").textValue());
        assertEquals("DIRECT", projection.at("/pipeline/execution").textValue());
        assertEquals("A", projection.at("/steps/0/id").textValue());
        assertEquals("normalize", projection.at("/steps/0/handler").textValue());
        assertEquals("B", projection.at("/steps/1/id").textValue());
        assertEquals(1, projection.at("/steps/1/retryLimit").intValue());
        assertEquals("C", projection.at("/steps/2/id").textValue());
        assertEquals("NON_NULL", projection.at("/types/0/fields/0/nullability").textValue());
        assertEquals("traceId", projection.at("/contextKeys/1").textValue());
        assertEquals("LOCAL_SHELL", projection.at("/policy/retryOwner").textValue());
        assertEquals("REJECT", projection.at("/policy/legacyPolicy").textValue());
        assertEquals(sha256(Files.readAllBytes(temp.resolve("pipeline.yaml"))),
            projection.at("/provenance/dslSha256").textValue());
        assertEquals(sha256(Files.readAllBytes(temp.resolve("bindings.json"))),
            projection.at("/provenance/bindingsSha256").textValue());

        String application = Files.readString(output.resolve("application.ts"));
        assertTrue(application.contains("export type StepId = \"A\" | \"B\" | \"C\";"));
        assertTrue(application.contains("\"apply\": Handler<Models.FirstValue, Models.SecondValue>;"));
        assertTrue(application.contains("return runtime.load("));
        assertTrue(application.contains("readonly kind: \"REJECTED\"; readonly diagnostic: string"));
        assertTrue(application.contains(json.writeValueAsString(Files.readString(projectionFile))));
        assertTrue(application.contains(sha256(Files.readAllBytes(projectionFile))));

        Path repeated = temp.resolve("repeated");
        generate(DSL, BINDINGS, repeated);
        for (String name : Set.of("projection.json", "models.ts", "application.ts")) {
            assertEquals(-1L, Files.mismatch(output.resolve(name), repeated.resolve(name)));
        }

        Path reordered = temp.resolve("reordered");
        String reorderedDsl = DSL.replace(FIRST + SECOND,
            FIRST.replace("name: A", "name: B") + SECOND.replace("name: B", "name: A"));
        String reorderedBindings = BINDINGS
            .replace(A_HANDLER, handler("A", "normalize", "FirstValue", "SecondValue", 0))
            .replace(B_HANDLER, handler("B", "apply", "InputValue", "FirstValue", 1));
        generate(reorderedDsl, reorderedBindings, reordered);
        JsonNode newProjection = json.readTree(reordered.resolve("projection.json").toFile());
        assertEquals("B", newProjection.at("/steps/0/id").textValue());
        assertEquals("apply", newProjection.at("/steps/0/handler").textValue());
        assertEquals("InputValue", newProjection.at("/steps/0/inputType").textValue());
        assertEquals(1, newProjection.at("/steps/0/retryLimit").intValue());
        assertEquals("A", newProjection.at("/steps/1/id").textValue());
        assertEquals("normalize", newProjection.at("/steps/1/handler").textValue());
        assertEquals("SecondValue", newProjection.at("/steps/1/outputType").textValue());
        assertEquals(projection.at("/steps/2"), newProjection.at("/steps/2"));
        rejects(reorderedDsl, BINDINGS, "HANDLER_TYPE");
        assertEquals(-1L, Files.mismatch(output.resolve("models.ts"), reordered.resolve("models.ts")));
        exportTypeScriptSmokeFixture();
    }

    @Test
    void rejectsUnsupportedShapeAndCanonicalTypesBeforePublishing() throws IOException {
        rejects(DSL + "futureHook: true\n", BINDINGS, "unsupported keys [futureHook]");
        rejects(DSL.replace("transport: LOCAL", "transport: REST"), BINDINGS, "transport: LOCAL");
        rejects(DSL.replace("platform: COMPUTE", "platform: FUNCTION"), BINDINGS, "platform: COMPUTE");
        rejects(DSL.replace("basePackage: org.example", "basePackage: false"), BINDINGS, ".basePackage");
        rejects(DSL.replace("name: A,", "name: A, await: {},"), BINDINGS, ".steps[0]");
        rejects(DSL.replace("name: B,", "name: B, execution: {blocking: true},"), BINDINGS, "execution");
        rejects(DSL.replace("name: C,", "name: C, kind: command,"), BINDINGS, "kind");
        rejects(DSL.replace("ONE_TO_ONE", "ONE_TO_MANY"), BINDINGS, ".cardinality");
        rejects(DSL.replace(THIRD, "").replace("output: OutputValue }\ntypes:", "output: SecondValue }\ntypes:"),
            BINDINGS, "exactly three steps");
        rejects(DSL.replace("[value, int32]", "[value, int64]"), BINDINGS, "non-null value:int32");
        rejects(DSL.replace("[value, int32]", "['value?', int32]"), BINDINGS, "non-null value:int32");
        rejects(DSL.replace("[value, int32]", "[value, 'int32?']"), BINDINGS, "non-null value:int32");
        rejects(DSL.replace("name: B, cardinality", "name: A, cardinality"), BINDINGS, "STEP_ID");
        rejects(DSL.replace(FIRST, FIRST.replace("input: InputValue", "input: Missing")), BINDINGS, "Missing");
        rejects(DSL.replace(FIRST + SECOND, SECOND + FIRST), BINDINGS, "first step");
        rejects(DSL.replace(SECOND, SECOND.replace("output: SecondValue", "output: FirstValue"))
            .replace(THIRD, THIRD.replace("input: SecondValue", "input: FirstValue")), BINDINGS,
            "two authored steps");
    }

    @Test
    void rejectsInvalidBindingsBeforePublishing() throws IOException {
        rejects(DSL, BINDINGS.replace("," + A_HANDLER, ""), "MISSING_HANDLER");
        rejects(DSL, BINDINGS.replace(A_HANDLER, B_HANDLER), "AMBIGUOUS_HANDLER");
        rejects(DSL, BINDINGS.replace("\"normalize\"", "\"apply\""), "AMBIGUOUS_HANDLER");
        rejects(DSL, BINDINGS.replace(A_HANDLER, A_HANDLER.replace("InputValue", "Wrong")), "HANDLER_TYPE");
        rejects(DSL, BINDINGS.replace(A_HANDLER, A_HANDLER + "," + A_HANDLER
            .replace("\"A\"", "\"D\"").replace("\"normalize\"", "\"extra\"")), "EXTRA_HANDLER");
        rejects(DSL, BINDINGS.replace("0}", "3}"), "UNSUPPORTED_POLICY");
        rejects(DSL, BINDINGS.replace("0}", "0.00000000000000000000000001}"), "CONFIG_NUMBER");
        rejects(DSL, BINDINGS.replace("0}", "false}"), "CONFIG_NUMBER");
        rejects(DSL, BINDINGS.replace("0}", "0,\"retryLimit\":0}"), "BINDINGS_FORMAT");
        rejects(DSL, BINDINGS + "{}", "BINDINGS_FORMAT");
        rejects(DSL, BINDINGS.replace("policy/1", "policy/2"), "BINDING_PROFILE");
        rejects(DSL, BINDINGS.replace("node-abc", "wrong-abc"), "IDENTITY");
    }

    @Test
    void rejectsAnOutputDirectoryContainingPreviousArtifacts() throws IOException {
        Path output = Files.createDirectory(temp.resolve("generated"));
        Path prior = Files.writeString(output.resolve("projection.json"), "prior");
        assertThrows(IOException.class, () -> generate(DSL, BINDINGS, output));
        assertEquals("prior", Files.readString(prior));
        assertFalse(Files.exists(output.resolve("application.ts")));
    }

    private String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void exportTypeScriptSmokeFixture() throws IOException {
        String smokeDirectory = System.getProperty("tpf.nodeGeneration.smokeDir", "");
        if (!smokeDirectory.isBlank()) {
            Path smoke = Path.of(smokeDirectory);
            generate(DSL, BINDINGS, smoke);
            try (var fixture = Objects.requireNonNull(
                getClass().getResourceAsStream("/node-local/handler-types.ts"))) {
                Files.copy(fixture, smoke.resolve("handler-types.ts"));
            }
        }
    }

    private void rejects(String dsl, String bindings, String expectedDiagnostic) throws IOException {
        Path output = temp.resolve("rejected");
        var failure = assertThrows(IllegalArgumentException.class, () -> generate(dsl, bindings, output));
        assertTrue(failure.getMessage().contains(expectedDiagnostic), failure::getMessage);
        assertFalse(Files.exists(output));
    }

    private void generate(String dsl, String bindings, Path output) throws IOException {
        Path pipeline = Files.writeString(temp.resolve("pipeline.yaml"), dsl);
        Path bindingFile = Files.writeString(temp.resolve("bindings.json"), bindings);
        NodeLocalCompiler.generate(pipeline, bindingFile, output);
    }
}
