/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.pipelineframework.proto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineIdlSnapshot;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;

import static org.junit.jupiter.api.Assertions.*;

class PipelineProtoRenderersTest {

    @TempDir
    Path tempDir;

    @Test
    void rendersV3ProductsThroughFocusedCollaborators() throws Exception {
        Path configPath = tempDir.resolve("pipeline.yaml");
        Files.writeString(configPath, """
            version: 3
            appName: Renderers
            basePackage: com.example.renderers
            transport: GRPC
            types:
              Request:
                fields: [[reference, payload_ref]]
              Result:
                fields: [[id, uuid]]
              Outcome:
                variants:
                  approved: Result
                  rejected: Result
            aspects:
              audit:
                enabled: true
                position: BEFORE_STEP
                config:
                  enabledTargets: [GRPC_SERVICE]
            steps:
              - name: Charge Card
                cardinality: ONE_TO_ONE
                input: Request
                output: Outcome
                execution:
                  mode: REMOTE
                  operatorId: charge-card
                  protocol: PROTOBUF_HTTP_V1
                  target:
                    urlConfigKey: remote.charge.url
            """);
        PipelineTemplateConfig config = new PipelineTemplateConfigLoader().load(configPath);
        PipelineIdlSnapshot state = PipelineIdlSnapshot.from(config);
        PipelineStepNormalizer normalizer = new PipelineStepNormalizer();
        List<ResolvedStep> steps = normalizer.resolveV3AliasContracts(
            normalizer.normalizeSteps(config.steps(), true, true), config.typeModel());
        ResolvedStep step = steps.getFirst();

        String types = new PipelineTypesProtoRenderer().renderV3(config.basePackage(), config.typeModel(), state);
        assertTrue(types.contains("message PayloadReference"));
        assertTrue(types.contains("oneof value"));
        assertTrue(types.contains("Result approved = 1;"));

        String stepProto = new PipelineStepProtoRenderer().renderStepProto(
            config.basePackage(), step, step, true, normalizer.toAspectDefinitions(config.aspects()), true,
            "pipeline-types.proto");
        assertTrue(stepProto.contains("rpc remoteProcess(Request) returns (Outcome);"));
        assertTrue(stepProto.contains("service ObserveAuditRequestSideEffectService"));

        String orchestrator = new PipelineOrchestratorProtoRenderer().renderOrchestratorProto(
            config.basePackage(), steps, true, "pipeline-types.proto");
        assertTrue(orchestrator.contains("rpc Run (Request) returns (Outcome);"));
        assertTrue(orchestrator.contains("rpc CompleteAwait (CompleteAwaitRequest) returns (CompleteAwaitResponse);"));

        new ExternalStepHostContractRenderer().write(config.basePackage(), tempDir, steps, "pipeline-types.proto");
        String manifest = Files.readString(tempDir.resolve("external-step-hosts.json"));
        assertTrue(manifest.contains("\"protocolVersion\" : \"tpf.external-step-hosts.v1\""));
        assertTrue(manifest.contains("\"operatorId\" : \"charge-card\""));
        assertTrue(Files.readString(tempDir.resolve("EXTERNAL-STEP-HOSTS.md"))
            .contains("| Charge Card | `charge-card` |"));
        new ExternalStepHostContractRenderer().write(config.basePackage(), tempDir, List.of(), "pipeline-types.proto");
        assertFalse(Files.exists(tempDir.resolve("external-step-hosts.json")));
        assertFalse(Files.exists(tempDir.resolve("EXTERNAL-STEP-HOSTS.md")));

        PipelineIdlSnapshotWriter writer = new PipelineIdlSnapshotWriter();
        Path lockPath = writer.resolveIdlStatePath(configPath);
        writer.writeIdlSnapshot(tempDir, config, state, state, lockPath, true);
        assertArrayEquals(Files.readAllBytes(lockPath), Files.readAllBytes(
            tempDir.resolve("target/generated-resources/META-INF/pipeline/idl.json")));
    }

    @Test
    void checksLockBaselineBeforeBootstrapReplacesIt() throws Exception {
        Path baselineConfigPath = tempDir.resolve("baseline.yaml");
        Files.writeString(baselineConfigPath, """
            version: 3
            appName: LockBaseline
            basePackage: com.example.lock
            types:
              Request:
                fields: [[id, uuid]]
            steps: []
            """);
        PipelineTemplateConfig baselineConfig = new PipelineTemplateConfigLoader().load(baselineConfigPath);
        PipelineIdlSnapshot baselineState = PipelineIdlSnapshot.from(baselineConfig);
        PipelineIdlSnapshotWriter writer = new PipelineIdlSnapshotWriter();
        Path lockPath = writer.resolveIdlStatePath(baselineConfigPath);
        writer.writeIdlSnapshot(tempDir, baselineConfig, baselineState, baselineState, lockPath, true);
        byte[] baselineBytes = Files.readAllBytes(lockPath);

        Path changedConfigPath = tempDir.resolve("changed.yaml");
        Files.writeString(changedConfigPath, """
            version: 3
            appName: LockBaseline
            basePackage: com.example.lock
            types:
              Other:
                fields: [[id, uuid]]
            steps: []
            """);
        PipelineTemplateConfig changedConfig = new PipelineTemplateConfigLoader().load(changedConfigPath);
        PipelineIdlSnapshot changedState = PipelineIdlSnapshot.from(changedConfig);

        System.setProperty("tpf.idl.compat.baseline", lockPath.toString());
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> writer.writeIdlSnapshot(tempDir, changedConfig, changedState, baselineState, lockPath, true));
            assertTrue(error.getMessage().contains("Missing type in current IDL: Request"));
        } finally {
            System.clearProperty("tpf.idl.compat.baseline");
        }
        assertArrayEquals(baselineBytes, Files.readAllBytes(lockPath));
    }
}
