/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pipelineframework.compiler.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.compiler.node.NodeLocalCompiler;

class PythonLocalCompilerTest {
    private static final String FIRST =
        "  - { name: Normalize, cardinality: ONE_TO_ONE, input: InputValue, output: FirstValue }\n";
    private static final String SECOND =
        "  - { name: Apply, cardinality: ONE_TO_ONE, input: FirstValue, output: SecondValue }\n";
    private static final String THIRD =
        "  - { name: Finish, cardinality: ONE_TO_ONE, input: SecondValue, output: OutputValue }\n";
    private static final String DSL = """
        version: 3
        appName: local-invoice
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
    private static final String NORMALIZE = handler("Normalize", "normalize", "InputValue", "FirstValue", 0);
    private static final String APPLY = handler("Apply", "class", "FirstValue", "SecondValue", 1);
    private static final String FINISH = handler("Finish", "finish", "SecondValue", "OutputValue", 0);
    private static final String BINDINGS = """
        {
          "schemaVersion":1,
          "profile":"tpf-polyglot-unary-policy/1",
          "pipelineId":"local-invoice",
          "handlers":[%s,%s,%s]
        }
        """.formatted(FINISH, APPLY, NORMALIZE);
    private static final Set<String> PYTHON_FILES = Set.of("projection.json", "models.py", "application.py");
    private static final List<String> SMOKE_FIXTURES = List.of(
        "check_types.py", "test_generated_bindings.py", "type_negative.py", "type_positive.py");

    private static String handler(String step, String name, String input, String output, int retries) {
        return """
            {"step":"%s","handler":"%s","inputType":"%s","outputType":"%s","retryLimit":%d}
            """.formatted(step, name, input, output, retries).strip();
    }

    @TempDir
    Path temp;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void bothTargetsProjectTheSameAdmittedSemanticsAndProvenance() throws IOException {
        Path pipeline = Files.writeString(temp.resolve("pipeline.yaml"), DSL);
        Path bindings = Files.writeString(temp.resolve("bindings.json"), BINDINGS);
        Path node = temp.resolve("node");
        Path python = temp.resolve("python");
        NodeLocalCompiler.generate(pipeline, bindings, node);
        PythonLocalCompiler.generate(pipeline, bindings, python);
        try (var entries = Files.list(python)) {
            assertEquals(PYTHON_FILES, entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
        assertEquals(Files.readString(node.resolve("projection.json")).replace("compiler-node-local", "compiler-python-local"),
            Files.readString(python.resolve("projection.json")));
        var projection = json.readTree(python.resolve("projection.json").toFile());
        assertEquals("Normalize", projection.at("/steps/0/id").textValue());
        assertEquals("class", projection.at("/steps/1/handler").textValue());
        assertEquals(1, projection.at("/steps/1/retryLimit").intValue());
        assertEquals("Finish", projection.at("/steps/2/id").textValue());
        String application = Files.readString(python.resolve("application.py"));
        assertTrue(application.contains("\"class\": Handler[_models.FirstValue, _models.SecondValue]"));
        assertTrue(application.contains("return runtime.load("));

        Path repeated = temp.resolve("repeated");
        PythonLocalCompiler.generate(pipeline, bindings, repeated);
        for (String name : PYTHON_FILES) {
            assertEquals(-1L, Files.mismatch(python.resolve(name), repeated.resolve(name)), name);
        }
        exportSmokeFixture(pipeline, bindings);
    }

    @Test
    void followsChangedAuthoredOrderAndRejectsStaleBindings() throws IOException {
        String reordered = DSL.replace(FIRST + SECOND,
            FIRST.replace("Normalize", "Apply") + SECOND.replace("Apply", "Normalize"));
        String reorderedBindings = BINDINGS.replace(APPLY, handler("Apply", "class", "InputValue", "FirstValue", 1))
            .replace(NORMALIZE, handler("Normalize", "normalize", "FirstValue", "SecondValue", 0));
        Path python = temp.resolve("reordered");
        generate(reordered, reorderedBindings, python);
        var projection = json.readTree(python.resolve("projection.json").toFile());
        assertEquals("Apply", projection.at("/steps/0/id").textValue());
        assertEquals("class", projection.at("/steps/0/handler").textValue());
        assertEquals(1, projection.at("/steps/0/retryLimit").intValue());
        assertEquals("Normalize", projection.at("/steps/1/id").textValue());
        assertEquals("Finish", projection.at("/steps/2/id").textValue());
        rejects(reordered, BINDINGS, "HANDLER_TYPE");
    }

    @Test
    void rejectsUnsupportedFeaturesAndInvalidBindingsBeforeWritingOutput() throws IOException {
        rejects(DSL.replace("transport: LOCAL", "transport: REST"), BINDINGS, "transport: LOCAL");
        rejects(DSL.replace("name: Normalize,", "name: Normalize, await: {},"), BINDINGS, "unsupported keys [await]");
        rejects(DSL.replace("[value, int32]", "[value, int64]"), BINDINGS, "non-null value:int32");
        rejects(DSL, BINDINGS.replace("," + NORMALIZE, ""), "MISSING_HANDLER");
        rejects(DSL, BINDINGS.replace(NORMALIZE, APPLY), "AMBIGUOUS_HANDLER");
        rejects(DSL, BINDINGS.replace(APPLY, APPLY.replace(":1}", ":3}")), "UNSUPPORTED_POLICY");
    }

    @Test
    void validatesClassNamesOnlyForTheSelectedLanguage() throws IOException {
        rejects(DSL.replace("FirstValue", "lambda"), BINDINGS.replace("FirstValue", "lambda"),
            "canonical name cannot be used as a Python class declaration");
        Path python = temp.resolve("python");
        generate(DSL.replace("FirstValue", "satisfies"), BINDINGS.replace("FirstValue", "satisfies"), python);
        assertTrue(Files.readString(python.resolve("models.py")).contains("class satisfies:"));
        var nodeFailure = assertThrows(IllegalArgumentException.class,
            () -> NodeLocalCompiler.generate(temp.resolve("pipeline.yaml"), temp.resolve("bindings.json"), temp.resolve("node")));
        assertTrue(nodeFailure.getMessage().contains("TypeScript type declaration"), nodeFailure::getMessage);
    }

    private void exportSmokeFixture(Path pipeline, Path bindings) throws IOException {
        String smokeDirectory = System.getProperty("tpf.pythonGeneration.smokeDir", "");
        if (!smokeDirectory.isBlank()) {
            Path smoke = Path.of(smokeDirectory);
            PythonLocalCompiler.generate(pipeline, bindings, smoke.resolve("generated"));
            for (String name : SMOKE_FIXTURES) {
                try (var source = Objects.requireNonNull(getClass().getResourceAsStream("/python-local/" + name))) {
                    Files.copy(source, smoke.resolve(name));
                }
            }
        }
    }

    private void rejects(String dsl, String bindings, String diagnostic) throws IOException {
        Path rejected = temp.resolve("rejected");
        var failure = assertThrows(IllegalArgumentException.class, () -> generate(dsl, bindings, rejected));
        assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
        assertFalse(Files.exists(rejected));
    }

    private void generate(String dsl, String bindings, Path output) throws IOException {
        Path pipeline = Files.writeString(temp.resolve("pipeline.yaml"), dsl);
        Path nativeBindings = Files.writeString(temp.resolve("bindings.json"), bindings);
        PythonLocalCompiler.generate(pipeline, nativeBindings, output);
    }
}
