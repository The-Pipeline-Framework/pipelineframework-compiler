/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pipelineframework.compiler.polyglot;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.pipelineframework.config.pipeline.PipelineYamlDocumentLoader;
import org.pipelineframework.config.template.PipelineFieldNullability;
import org.pipelineframework.config.template.PipelineFieldPresence;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.config.template.PipelineTemplateTypeReference;
import org.pipelineframework.processor.routing.AuthoredConsumerValidator;
import org.yaml.snakeyaml.error.YAMLException;

/** Projects the admitted canonical v3 model for the finite unary LOCAL profile. */
final class LocalProfile {
    private static final int SCHEMA_VERSION = 1;
    private static final String PROFILE = "tpf-polyglot-unary-policy/1";
    private static final Pattern PIPELINE_ID = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Set<String> ROOT_KEYS = Set.of(
        "version", "appName", "basePackage", "transport", "platform", "contract", "types", "steps");

    private final ObjectMapper bindingsReader = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    record InputDigests(String dslSha256, String bindingsSha256) { }
    record Pipeline(String id, String inputType, String outputType, String transport, String platform,
                    String execution) { }
    record Field(String name, String type, String presence, String nullability) { }
    record Type(String id, List<Field> fields) {
        Type {
            fields = List.copyOf(fields);
        }
    }
    record Step(String id, String inputType, String outputType, String handler, int retryLimit) { }
    record Policy(String failureMapping, String retryOwner, String resultMapping, String cancellation,
                  String context, String recovery, String legacyPolicy) { }
    record Projection(int schemaVersion, String profile, String kind, Pipeline pipeline,
                      List<String> requiredCapabilities, List<Type> types, List<Step> steps,
                      List<String> contextKeys, Policy policy, InputDigests provenance) {
        Projection {
            requiredCapabilities = List.copyOf(requiredCapabilities);
            types = List.copyOf(types);
            steps = List.copyOf(steps);
            contextKeys = List.copyOf(contextKeys);
        }
    }

    private record Handler(String name, String inputType, String outputType, int retryLimit, String location) { }

    PipelineTemplateConfig load(Path source, byte[] bytes) throws IOException {
        // The canonical loader takes a Path. Give both gates the same source snapshot used
        // for provenance, with no processor, ambient overrides, or application Java discovery.
        Path snapshot = Files.createTempFile("tpf-local-pipeline-", ".yaml");
        try {
            Files.write(snapshot, bytes);
            admitShape(new PipelineYamlDocumentLoader().load(snapshot), source.toString());
            var warnings = new ArrayList<String>();
            var disabledOverrides = Map.<String, String>of();
            PipelineTemplateConfig model;
            try {
                model = new PipelineTemplateConfigLoader(disabledOverrides::get, disabledOverrides::get,
                    warnings::add).load(snapshot);
                new AuthoredConsumerValidator().validate(model);
            } catch (IllegalArgumentException failure) {
                throw reject(source.toString(), "DSL_MODEL", failure.getMessage());
            }
            if (!warnings.isEmpty()) {
                throw reject(source.toString(), "DSL_WARNING", String.join(" / ", warnings));
            }
            return model;
        } catch (YAMLException | IllegalStateException failure) {
            throw reject(source.toString(), "DSL_MODEL", failure.getMessage().replace(snapshot.toString(), source.toString()));
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    /**
     * Rejects authored features the general loader can ignore or default. The shared loader
     * still owns YAML parsing, field syntax, type resolution, and flow contracts.
     */
    private void admitShape(Object rootValue, String location) {
        Map<?, ?> root = map(rootValue, location);
        keys(root.keySet(), ROOT_KEYS, ROOT_KEYS, location);
        if (!Integer.valueOf(3).equals(root.get("version")) || !"LOCAL".equals(root.get("transport"))
            || !"COMPUTE".equals(root.get("platform"))) {
            throw reject(location, "UNSUPPORTED", "requires explicit version: 3, transport: LOCAL, platform: COMPUTE");
        }
        requireText(root.get("appName"), location + ".appName");
        requireText(root.get("basePackage"), location + ".basePackage");
        String contractLocation = location + ".contract";
        var contract = map(root.get("contract"), contractLocation);
        Set<String> endpoints = Set.of("input", "output");
        keys(contract.keySet(), endpoints, endpoints, contractLocation);
        endpoints.forEach(key -> requireText(contract.get(key), contractLocation + "." + key));
        String typesLocation = location + ".types";
        var types = map(root.get("types"), typesLocation);
        for (var type : types.entrySet()) {
            requireText(type.getKey(), typesLocation + " key");
            String typeLocation = typesLocation + "." + type.getKey();
            var definition = map(type.getValue(), typeLocation);
            keys(definition.keySet(), Set.of("fields"), Set.of("fields"), typeLocation);
            var fields = list(definition.get("fields"), typeLocation + ".fields");
            for (int index = 0; index < fields.size(); index++) {
                String fieldLocation = typeLocation + ".fields[" + index + "]";
                if (fields.get(index) instanceof List<?> tuple) {
                    if (tuple.size() != 2) {
                        throw reject(fieldLocation, "SHAPE", "field tuple must contain a name and type");
                    }
                    tuple.forEach(value -> requireText(value, fieldLocation));
                } else {
                    var field = map(fields.get(index), fieldLocation);
                    keys(field.keySet(), Set.of("name", "type", "presence", "nullability"),
                        Set.of("name", "type"), fieldLocation);
                    field.forEach((key, value) -> requireText(value, fieldLocation + "." + key));
                }
            }
        }
        var steps = list(root.get("steps"), location + ".steps");
        Set<String> stepKeys = Set.of("name", "cardinality", "input", "output");
        for (int index = 0; index < steps.size(); index++) {
            String stepLocation = location + ".steps[" + index + "]";
            var step = map(steps.get(index), stepLocation);
            keys(step.keySet(), stepKeys, stepKeys, stepLocation);
            step.forEach((key, value) -> requireText(value, stepLocation + "." + key));
        }
    }

    Projection project(PipelineTemplateConfig model, Path dsl, Path bindingFile, byte[] bindingBytes,
                       InputDigests digests, String kind) {
        String location = dsl.toString();
        if (!PIPELINE_ID.matcher(model.appName()).matches()) {
            throw reject(location + ".appName", "IDENTITY", "expected a lowercase pipeline identifier");
        }
        if (model.steps().size() != 3) {
            throw reject(location + ".steps", "UNSUPPORTED", "the qualified profile requires exactly three steps");
        }

        var canonicalTypes = model.typeModel();
        List<Type> types = canonicalTypes.definitions().values().stream()
            .sorted(Comparator.comparing(PipelineTemplateTypeDefinition::name))
            .map(definition -> projectType(definition, location)).toList();
        var boundaryTypes = new TreeSet<String>();
        String preceding = model.inputContract();
        boundaryTypes.add(preceding);
        Map<String, Handler> handlers = readBindings(bindingFile, bindingBytes, model.appName());
        var steps = new ArrayList<Step>();
        var stepIds = new TreeSet<String>();
        for (var step : model.steps()) {
            String stepLocation = location + ".steps[" + steps.size() + "] (" + step.name() + ")";
            if (!IDENTIFIER.matcher(step.name()).matches() || !stepIds.add(step.name())) {
                throw reject(stepLocation, "STEP_ID", "expected a unique logical step identifier");
            }
            if (!"ONE_TO_ONE".equals(step.cardinality())) {
                throw reject(stepLocation + ".cardinality", "UNSUPPORTED", "requires ONE_TO_ONE");
            }
            if (!canonicalTypes.isAssignable(preceding, step.inputTypeName())) {
                throw reject(stepLocation + ".input", "INCOMPATIBLE_LINK",
                    preceding + " is not assignable to " + step.inputTypeName());
            }
            preceding = step.outputTypeName();
            boundaryTypes.add(preceding);
            var handler = handlers.remove(step.name());
            if (handler == null) {
                throw reject(bindingFile + ".handlers", "MISSING_HANDLER", "missing handler for step " + step.name());
            }
            if (!handler.inputType().equals(step.inputTypeName()) || !handler.outputType().equals(step.outputTypeName())) {
                throw reject(handler.location(), "HANDLER_TYPE",
                    "handler must bind " + step.inputTypeName() + " -> " + step.outputTypeName());
            }
            steps.add(new Step(step.name(), step.inputTypeName(), step.outputTypeName(), handler.name(),
                handler.retryLimit()));
        }
        if (!handlers.isEmpty()) {
            throw reject(bindingFile + ".handlers", "EXTRA_HANDLER", "bindings name absent steps " + handlers.keySet());
        }
        if (!canonicalTypes.isAssignable(preceding, model.outputContract())) {
            throw reject(location + ".contract.output", "INCOMPATIBLE_LINK",
                preceding + " is not assignable to " + model.outputContract());
        }
        if (boundaryTypes.size() != 4 || !boundaryTypes.equals(canonicalTypes.definitions().keySet())) {
            throw reject(location + ".types", "UNSUPPORTED_TYPE",
                "expected four distinct canonical records for pipeline input and the three step outputs");
        }
        return new Projection(SCHEMA_VERSION, PROFILE, kind,
            new Pipeline(model.appName(), model.inputContract(), model.outputContract(), model.transport(),
                model.platform().name(), "DIRECT"),
            List.of("pure", "linear", "unary", "local", "retry", "cancellation", "context"),
            types, steps, List.of("executionId", "traceId"),
            new Policy("structured-failure/1", "LOCAL_SHELL", "explicit-value/1", "first-accepted-terminal/1",
                "explicit-snapshot/1", "REJECT", "REJECT"), digests);
    }

    private Type projectType(PipelineTemplateTypeDefinition definition, String location) {
        String typeId = definition.name();
        String typeLocation = location + ".types." + typeId;
        if (!(definition instanceof PipelineTemplateTypeDefinition.RecordType record)
            || record.fields().size() != 1) {
            throw reject(typeLocation, "UNSUPPORTED_TYPE", "expected one required non-null value:int32 field");
        }
        var field = record.fields().getFirst();
        if (!"value".equals(field.name()) || field.repeated()
            || !(field.type() instanceof PipelineTemplateTypeReference.Scalar scalar) || !"int32".equals(scalar.name())
            || field.presence() != PipelineFieldPresence.REQUIRED
            || field.nullability() != PipelineFieldNullability.NON_NULL) {
            throw reject(typeLocation + ".fields[0]", "UNSUPPORTED_TYPE", "requires a required non-null value:int32");
        }
        return new Type(typeId, List.of(new Field(field.name(), scalar.name(), field.presence().name(),
            field.nullability().name())));
    }

    private Map<String, Handler> readBindings(Path file, byte[] bytes, String pipelineId) {
        String location = file.toString();
        JsonNode root;
        try {
            root = bindingsReader.readTree(bytes);
        } catch (IOException failure) {
            throw reject(location, "BINDINGS_FORMAT", failure.getMessage());
        }
        jsonKeys(root, Set.of("schemaVersion", "profile", "pipelineId", "handlers"), location);
        if (integer(root.path("schemaVersion"), location + ".schemaVersion") != SCHEMA_VERSION
            || !PROFILE.equals(root.path("profile").textValue())) {
            throw reject(location, "BINDING_PROFILE", "requires schemaVersion 1 and profile " + PROFILE);
        }
        if (!pipelineId.equals(root.path("pipelineId").textValue())) {
            throw reject(location + ".pipelineId", "IDENTITY", "expected " + pipelineId);
        }
        if (!root.path("handlers").isArray()) {
            throw reject(location + ".handlers", "SHAPE", "expected a JSON array");
        }
        var bindings = new LinkedHashMap<String, Handler>();
        var handlerNames = new TreeSet<String>();
        int index = 0;
        for (JsonNode binding : root.path("handlers")) {
            String at = location + ".handlers[" + index++ + "]";
            jsonKeys(binding, Set.of("step", "handler", "inputType", "outputType", "retryLimit"), at);
            String step = requireText(binding.path("step").textValue(), at + ".step");
            String handler = requireText(binding.path("handler").textValue(), at + ".handler");
            if (!IDENTIFIER.matcher(handler).matches()) {
                throw reject(at + ".handler", "HANDLER_ID", "expected an identifier");
            }
            int retryLimit = integer(binding.path("retryLimit"), at + ".retryLimit");
            if (retryLimit < 0 || retryLimit > 2) {
                throw reject(at + ".retryLimit", "UNSUPPORTED_POLICY", "expected a retry budget of 0, 1, or 2");
            }
            var value = new Handler(handler, requireText(binding.path("inputType").textValue(), at + ".inputType"),
                requireText(binding.path("outputType").textValue(), at + ".outputType"), retryLimit, at);
            if (bindings.putIfAbsent(step, value) != null || !handlerNames.add(handler)) {
                throw reject(at, "AMBIGUOUS_HANDLER", "step and handler names must each be unique");
            }
        }
        return bindings;
    }

    private void jsonKeys(JsonNode node, Set<String> expected, String location) {
        if (node == null || !node.isObject()) {
            throw reject(location, "SHAPE", "expected a JSON object");
        }
        var actual = new TreeSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        keys(actual, expected, expected, location);
    }

    private void keys(Set<?> actual, Set<String> allowed, Set<String> required, String location) {
        var unsupported = new TreeSet<String>();
        actual.stream().filter(key -> !allowed.contains(key)).map(String::valueOf).forEach(unsupported::add);
        if (!unsupported.isEmpty()) {
            throw reject(location, "UNSUPPORTED", "unsupported keys " + unsupported);
        }
        var missing = new TreeSet<>(required);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw reject(location, "SHAPE", "missing keys " + missing);
        }
    }

    private Map<?, ?> map(Object value, String location) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw reject(location, "SHAPE", "expected a YAML mapping");
    }

    private List<?> list(Object value, String location) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw reject(location, "SHAPE", "expected a YAML list");
    }

    private String requireText(Object value, String location) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw reject(location, "SHAPE", "expected nonblank text");
    }

    private int integer(JsonNode node, String location) {
        if (node.isNumber()) {
            try {
                return node.decimalValue().intValueExact();
            } catch (ArithmeticException | NumberFormatException invalid) {
                throw reject(location, "CONFIG_NUMBER", "expected an exact integer");
            }
        }
        throw reject(location, "CONFIG_NUMBER", "expected an exact integer");
    }

    private IllegalArgumentException reject(String location, String code, String message) {
        return new IllegalArgumentException(location + " [" + code + "] " + message);
    }
}
