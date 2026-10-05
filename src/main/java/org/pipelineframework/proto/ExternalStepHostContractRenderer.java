/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.pipelineframework.proto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.pipelineframework.config.template.*;

final class ExternalStepHostContractRenderer {
    private static final String EXTERNAL_STEP_HOSTS_MANIFEST = "external-step-hosts.json";
    private static final String EXTERNAL_STEP_HOSTS_README = "EXTERNAL-STEP-HOSTS.md";
    private static final String EXTERNAL_STEP_HOSTS_PROTOCOL_VERSION = "tpf.external-step-hosts.v1";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    void write(String basePackage, Path outputDir, List<ResolvedStep> steps, String typesProtoName) {
        List<ExternalStepHostContract> contracts = externalStepHostContracts(basePackage, steps, typesProtoName);
        if (contracts.isEmpty()) {
            return;
        }
        ExternalStepHostManifest manifest = new ExternalStepHostManifest(
            EXTERNAL_STEP_HOSTS_PROTOCOL_VERSION, basePackage, typesProtoName, contracts);
        Path manifestPath = outputDir.resolve(EXTERNAL_STEP_HOSTS_MANIFEST);
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write JSON file: " + manifestPath, e);
        }
        Path readmePath = outputDir.resolve(EXTERNAL_STEP_HOSTS_README);
        try {
            Files.writeString(readmePath, renderExternalStepHostReadme(manifest));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write proto file: " + readmePath, e);
        }
    }

    private List<ExternalStepHostContract> externalStepHostContracts(
        String basePackage,
        List<ResolvedStep> steps,
        String typesProtoName
    ) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<ExternalStepHostContract> contracts = new ArrayList<>();
        for (ResolvedStep step : steps) {
            PipelineTemplateStepExecution execution = step.execution();
            if (execution == null || !execution.isRemote()) {
                continue;
            }
            contracts.add(new ExternalStepHostContract(
                step.name(),
                "Process" + step.serviceNameFormatted() + "Service",
                "remoteProcess",
                step.serviceName() + ".proto",
                typesProtoName,
                step.cardinality(),
                step.inputTypeName(),
                step.operationOutputTypeName(),
                execution.operatorId(),
                execution.protocol(),
                execution.timeoutMs(),
                targetMap(execution.target()),
                httpContract(execution.protocol()),
                payloadPolicy(execution.protocol())));
        }
        return List.copyOf(contracts);
    }

    private Map<String, String> targetMap(PipelineTemplateRemoteTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("Remote step host target requires exactly one of url or urlConfigKey");
        }
        boolean hasUrl = target.url() != null;
        boolean hasUrlConfigKey = target.urlConfigKey() != null;
        if (hasUrl == hasUrlConfigKey) {
            throw new IllegalArgumentException("Remote step host target requires exactly one of url or urlConfigKey");
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (hasUrl) {
            values.put("url", target.url());
        }
        if (hasUrlConfigKey) {
            values.put("urlConfigKey", target.urlConfigKey());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private Map<String, Object> httpContract(String protocol) {
        if ("ENVELOPE_HTTP_V1".equalsIgnoreCase(protocol)) {
            return envelopeHttpContract();
        }
        if ("PROTOBUF_HTTP_V1".equalsIgnoreCase(protocol)) {
            return protobufHttpContract();
        }
        throw new IllegalArgumentException("Unsupported remote step host protocol '" + protocol + "'");
    }

    private Map<String, Object> protobufHttpContract() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("method", "POST");
        contract.put("contentType", "application/x-protobuf");
        contract.put("accept", "application/x-protobuf");
        contract.put("successStatus", "2xx");
        contract.put("failureEnvelope", "google.rpc.Status");
        contract.put("headers", List.of(
            "x-tpf-correlation-id",
            "x-tpf-execution-id",
            "x-tpf-idempotency-key",
            "x-tpf-retry-attempt",
            "x-tpf-deadline-epoch-ms",
            "x-tpf-dispatch-ts-epoch-ms",
            "x-tpf-parent-item-id"));
        return Collections.unmodifiableMap(new LinkedHashMap<>(contract));
    }

    private Map<String, Object> envelopeHttpContract() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("method", "POST");
        contract.put("contentType", "application/vnd.tpf.envelope.v1+json");
        contract.put("accept", "application/vnd.tpf.envelope.v1+json");
        contract.put("successStatus", "2xx");
        contract.put("failureEnvelope", "tpf.envelope.v1 error payload or HTTP error body");
        contract.put("headers", List.of(
            "x-tpf-correlation-id",
            "x-tpf-execution-id",
            "x-tpf-idempotency-key",
            "x-tpf-retry-attempt",
            "x-tpf-deadline-epoch-ms",
            "x-tpf-dispatch-ts-epoch-ms",
            "x-tpf-parent-item-id"));
        return Collections.unmodifiableMap(new LinkedHashMap<>(contract));
    }

    private Map<String, Object> payloadPolicy(String protocol) {
        Map<String, Object> policy = new LinkedHashMap<>();
        if ("ENVELOPE_HTTP_V1".equalsIgnoreCase(protocol)) {
            policy.put("mode", "ENVELOPE");
            policy.put("control", "strict");
            policy.put("payload", List.of("json", "bytes", "ref"));
            policy.put("protocolVersion", "tpf.envelope.v1");
            return Collections.unmodifiableMap(new LinkedHashMap<>(policy));
        }
        if (!"PROTOBUF_HTTP_V1".equalsIgnoreCase(protocol)) {
            throw new IllegalArgumentException("Unsupported remote step host payload protocol '" + protocol + "'");
        }
        policy.put("mode", "PROTOBUF");
        policy.put("control", "http-headers");
        policy.put("payload", List.of("protobuf"));
        return Collections.unmodifiableMap(new LinkedHashMap<>(policy));
    }

    private String renderExternalStepHostReadme(ExternalStepHostManifest manifest) {
        StringBuilder builder = new StringBuilder();
        builder.append("# External Step Host Contracts\n\n");
        builder.append("This directory contains protobuf contracts for remote TPF step hosts.\n");
        builder.append("Remote step hosts implement immediate request/response boundaries. ");
        builder.append("If the result arrives later, model that boundary as an await step.\n\n");
        builder.append("- Protocol version: ").append(markdownCode(manifest.protocolVersion())).append('\n');
        builder.append("- Protobuf package: ").append(markdownCode(manifest.basePackage())).append('\n');
        builder.append("- Shared types proto: ").append(markdownCode(manifest.typesProto())).append("\n\n");
        builder.append("| Step | Operator id | Service | RPC | Request | Response | Proto |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (ExternalStepHostContract contract : manifest.steps()) {
            builder.append("| ")
                .append(markdownText(contract.step()))
                .append(" | ")
                .append(markdownCode(contract.operatorId()))
                .append(" | ")
                .append(markdownCode(contract.service()))
                .append(" | ")
                .append(markdownCode(contract.rpc()))
                .append(" | ")
                .append(markdownCode(contract.inputType()))
                .append(" | ")
                .append(markdownCode(contract.outputType()))
                .append(" | ")
                .append(markdownCode(contract.proto()))
                .append(" |\n");
        }
        builder.append("\n");
        builder.append("For `PROTOBUF_HTTP_V1`, hosts receive a `POST` body encoded as `application/x-protobuf` ");
        builder.append("using the request message and return the response message as `application/x-protobuf`.\n");
        builder.append("Non-2xx failures should return a `google.rpc.Status` protobuf body when possible.\n");
        builder.append("For `ENVELOPE_HTTP_V1`, hosts receive and return `application/vnd.tpf.envelope.v1+json`; ");
        builder.append("TPF owns the control metadata while the payload region may carry JSON, bytes, or a payload reference.\n");
        return builder.toString();
    }

    private String markdownText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace('\n', ' ').replace("|", "\\|");
    }

    private String markdownCode(String value) {
        String text = markdownText(value);
        if (text.isEmpty()) {
            return "";
        }
        String delimiter = "`";
        while (text.contains(delimiter)) {
            delimiter += "`";
        }
        return delimiter + text + delimiter;
    }

    private record ExternalStepHostManifest(
        String protocolVersion,
        String basePackage,
        String typesProto,
        List<ExternalStepHostContract> steps
    ) {
    }

    private record ExternalStepHostContract(
        String step,
        String service,
        String rpc,
        String proto,
        String typesProto,
        String cardinality,
        String inputType,
        String outputType,
        String operatorId,
        String protocol,
        Integer timeoutMs,
        Map<String, String> target,
        Map<String, Object> http,
        Map<String, Object> payloadPolicy
    ) {
    }
}
