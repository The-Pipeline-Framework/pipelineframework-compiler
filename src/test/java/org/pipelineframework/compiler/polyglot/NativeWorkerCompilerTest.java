/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pipelineframework.compiler.polyglot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.pipeline.PipelineJson;
import org.pipelineframework.orchestrator.PipelineBundleStepDescriptor;
import org.pipelineframework.orchestrator.release.PipelineContractDescriptor;

class NativeWorkerCompilerTest {
    private static final String PIPELINE = """
        version: 3
        appName: worker-qualification
        basePackage: org.example
        transport: LOCAL
        platform: COMPUTE
        contract: {input: InputValue, output: OutputValue}
        types:
          InputValue: {fields: [[value, int32]]}
          FirstValue: {fields: [[value, int32]]}
          SecondValue: {fields: [[value, int32]]}
          OutputValue: {fields: [[value, int32]]}
        steps:
          - {name: A, cardinality: ONE_TO_ONE, input: InputValue, output: FirstValue}
          - {name: B, cardinality: ONE_TO_ONE, input: FirstValue, output: SecondValue}
          - {name: C, cardinality: ONE_TO_ONE, input: SecondValue, output: OutputValue}
        """;
    private static final String BINDINGS = """
        {
          "schemaVersion": 1,
          "profile": "tpf-polyglot-unary-policy/1",
          "pipelineId": "worker-qualification",
          "handlers": [
            {"step":"B","handler":"apply","inputType":"FirstValue","outputType":"SecondValue","retryLimit":1},
            {"step":"A","handler":"normalize","inputType":"InputValue","outputType":"FirstValue","retryLimit":0},
            {"step":"C","handler":"finish","inputType":"SecondValue","outputType":"OutputValue","retryLimit":0}
          ]
        }
        """;

    @TempDir
    Path temp;
    private final ObjectMapper json = PipelineJson.mapper();

    @Test
    void emitsTheSameReleasedContractAndExactCompiledTruthForBothTargets() throws Exception {
        Path node = generate("node-worker", "node", PIPELINE, BINDINGS);
        Path python = generate("python-worker", "python", PIPELINE, BINDINGS);
        assertEquals(-1, Files.mismatch(node.resolve("pipeline-contract.json"), python.resolve("pipeline-contract.json")));
        var contract = readContract(node);
        assertEquals(contract, readContract(python));
        assertEquals(2, contract.schemaVersion());
        assertEquals("worker-qualification", contract.pipelineId());
        assertEquals("sha256:" + contract.contractHash(), contract.contractVersion());
        assertEquals("MONOLITH", contract.runtimeLayout());
        assertEquals("COMPUTE", contract.platform());
        assertEquals("LOCAL", contract.transport());
        assertEquals(List.of("local", "rest"), contract.capabilities().transitionWorkerProtocols());
        assertTrue(contract.capabilities().localTransitionExecution());
        assertFalse(contract.pluginHost());
        assertEquals(List.of("A", "B", "C"), contract.steps().stream().map(PipelineBundleStepDescriptor::authoredName).toList());
        assertEquals(List.of("InputValue", "FirstValue", "SecondValue"),
            contract.steps().stream().map(PipelineBundleStepDescriptor::inputTypeId).toList());
        assertEquals(List.of("FirstValue", "SecondValue", "OutputValue"),
            contract.steps().stream().map(PipelineBundleStepDescriptor::outputTypeId).toList());
        for (int index = 0; index < contract.steps().size(); index++) {
            var step = contract.steps().get(index);
            assertEquals(index, step.index());
            assertEquals("internal", step.kind());
            assertEquals("ONE_TO_ONE", step.cardinality());
            assertNull(step.runtimeClass());
            assertNull(step.clientClass());
        }
        assertEquals(Set.of("InputValue", "FirstValue", "SecondValue", "OutputValue"), contract.canonicalTypes().keySet());
        JsonNode types = json.readTree(node.resolve("pipeline-contract.json").toFile()).path("canonicalTypes");
        assertEquals(contract.canonicalCatalogFingerprint(), sha256(json.writeValueAsBytes(types)));
        for (var type : types) {
            assertEquals(type.path("definitionFingerprint").asText(), sha256(json.writeValueAsBytes(type.path("definition"))));
            assertFalse(type.has("runtimeClass"));
            assertEquals("int32", type.at("/definition/fields/0/type/id").asText());
        }
        assertArchive(node, "compiler-node-local");
        assertArchive(python, "compiler-python-local");
        assertNotEquals(-1, Files.mismatch(node.resolve("projection.json"), python.resolve("projection.json")));

        Path repeated = generate("node-worker", "node-repeated", PIPELINE, BINDINGS);
        assertEquals(-1, Files.mismatch(node.resolve("compiled-truth.zip"), repeated.resolve("compiled-truth.zip")));

        Path dsl = temp.resolve("pipeline.yaml");
        Path bindings = temp.resolve("native-bindings.json");
        Path localNode = temp.resolve("node-local");
        Path localPython = temp.resolve("python-local");
        NativeLocalCompiler.generate(NativeLocalCompiler.Target.NODE, dsl, bindings, localNode);
        NativeLocalCompiler.generate(NativeLocalCompiler.Target.PYTHON, dsl, bindings, localPython);
        assertLocalOutput(localNode, node, Set.of("application.ts", "models.ts", "projection.json"));
        assertLocalOutput(localPython, python, Set.of("application.py", "models.py", "projection.json"));
    }

    @Test
    void hashesLogicalStepOrderTypesAndRetryLimitsWithoutHashingLanguageBindings() throws Exception {
        var original = readContract(generate("node-worker", "original", PIPELINE, BINDINGS));
        Path renamedHandler = generate("python-worker", "renamed-handler", PIPELINE + "\n",
            BINDINGS.replace("normalize", "normalize_input"));
        assertEquals(original, readContract(renamedHandler));
        assertArchive(renamedHandler, "compiler-python-local");

        Path retry = generate("node-worker", "retry", PIPELINE, BINDINGS.replace("\"retryLimit\":1", "\"retryLimit\":2"));
        var changedRetry = readContract(retry);
        assertNotEquals(original.contractHash(), changedRetry.contractHash());
        assertEquals(original.canonicalCatalogFingerprint(), changedRetry.canonicalCatalogFingerprint());
        assertArchive(retry, "compiler-node-local");

        Path changedType = generate("node-worker", "changed-type", PIPELINE.replace("FirstValue", "MiddleValue"),
            BINDINGS.replace("FirstValue", "MiddleValue"));
        var changedContract = readContract(changedType);
        assertNotEquals(original.contractHash(), changedContract.contractHash());
        assertNotEquals(original.canonicalCatalogFingerprint(), changedContract.canonicalCatalogFingerprint());
        assertEquals("MiddleValue", changedContract.steps().getFirst().outputTypeId());
        assertArchive(changedType, "compiler-node-local");

        String reorderedDsl = PIPELINE.replace("name: A,", "name: swapped,")
            .replace("name: B,", "name: A,").replace("name: swapped,", "name: B,");
        String reorderedBindings = BINDINGS.replace("\"step\":\"A\"", "\"step\":\"swapped\"")
            .replace("\"step\":\"B\"", "\"step\":\"A\"").replace("\"step\":\"swapped\"", "\"step\":\"B\"");
        Path reordered = generate("node-worker", "reordered", reorderedDsl, reorderedBindings);
        var reorderedContract = readContract(reordered);
        assertNotEquals(original.contractHash(), reorderedContract.contractHash());
        assertEquals(List.of("B", "A", "C"), reorderedContract.steps().stream().map(PipelineBundleStepDescriptor::authoredName).toList());
        assertArchive(reordered, "compiler-node-local");
    }

    @Test
    void refusesUnadmittedGenerationBeforePublishing() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> generate("node-worker", "unsupported",
            PIPELINE.replace("ONE_TO_ONE", "ONE_TO_MANY"), BINDINGS));
        assertFalse(Files.exists(temp.resolve("unsupported")));
    }

    private Path generate(String target, String name, String dsl, String bindings) throws IOException {
        Path output = temp.resolve(name);
        NativeWorkerCompiler.generate(target, Files.writeString(temp.resolve("pipeline.yaml"), dsl),
            Files.writeString(temp.resolve("native-bindings.json"), bindings), output);
        return output;
    }

    private PipelineContractDescriptor readContract(Path output) throws IOException {
        return json.readValue(output.resolve("pipeline-contract.json").toFile(), PipelineContractDescriptor.class);
    }

    private void assertArchive(Path output, String kind) throws Exception {
        byte[] projectionBytes = Files.readAllBytes(output.resolve("projection.json"));
        byte[] contractBytes = Files.readAllBytes(output.resolve("pipeline-contract.json"));
        String projectionResource = "META-INF/pipeline/native-execution-projection.json";
        try (var zip = new ZipFile(output.resolve("compiled-truth.zip").toFile())) {
            assertEquals(List.of(projectionResource, PipelineContractDescriptor.RESOURCE_PATH),
                zip.stream().map(java.util.zip.ZipEntry::getName).toList());
            assertArrayEquals(projectionBytes, zip.getInputStream(zip.getEntry(projectionResource)).readAllBytes());
            assertArrayEquals(contractBytes, zip.getInputStream(zip.getEntry(PipelineContractDescriptor.RESOURCE_PATH)).readAllBytes());
        }

        JsonNode projection = json.readTree(projectionBytes);
        assertEquals(kind, projection.path("kind").asText());
        ObjectNode hashInput = (ObjectNode) json.readTree(contractBytes);
        String expectedHash = hashInput.remove("contractHash").asText();
        hashInput.remove("contractVersion");
        ObjectNode policy = hashInput.putObject("nativeExecutionPolicy");
        policy.set("profile", projection.path("profile"));
        var retryLimits = policy.putArray("retryLimits");
        projection.path("steps").forEach(step -> retryLimits.add(step.path("retryLimit").intValue()));
        assertEquals(expectedHash, sha256(json.writeValueAsBytes(hashInput)));
    }

    private void assertLocalOutput(Path local, Path worker, Set<String> expected) throws IOException {
        try (var entries = Files.list(local)) {
            assertEquals(expected, entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
        for (String name : expected) {
            assertEquals(-1, Files.mismatch(local.resolve(name), worker.resolve(name)), name);
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
