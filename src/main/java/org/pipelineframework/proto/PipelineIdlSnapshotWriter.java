/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.pipelineframework.proto;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineIdlSnapshot;
import org.pipelineframework.config.template.PipelineIdlCompatibilityChecker;

final class PipelineIdlSnapshotWriter {
    private static final String IDL_SNAPSHOT_PROPERTY = "tpf.idl.compat.baseline";
    private static final String IDL_SNAPSHOT_ENV = "TPF_IDL_COMPAT_BASELINE";
    private static final ObjectMapper IDL_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    Path resolveIdlStatePath(Path configPath) {
        String configFileName = configPath.getFileName().toString();
        String stateFileName = stateFileName(configFileName);
        return configPath.getParent().resolve(stateFileName);
    }

    private static String stateFileName(String configFileName) {
        if (configFileName.endsWith(".yaml")) {
            return configFileName.substring(0, configFileName.length() - ".yaml".length()) + ".idl.json";
        }
        if (configFileName.endsWith(".yml")) {
            return configFileName.substring(0, configFileName.length() - ".yml".length()) + ".idl.json";
        }
        return "pipeline.idl.json";
    }

    void writeIdlSnapshot(
        Path moduleDir,
        PipelineTemplateConfig config,
        PipelineIdlSnapshot snapshot,
        PipelineIdlSnapshot committedState,
        Path statePath,
        boolean bootstrap
    ) {
        Path outputPath = moduleDir.resolve("target")
            .resolve("generated-resources")
            .resolve("META-INF")
            .resolve("pipeline")
            .resolve("idl.json");
        try {
            Files.createDirectories(outputPath.getParent());
            IDL_MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), snapshot);
            if (bootstrap) {
                writeIdlLock(statePath, snapshot);
            } else if (committedState != null && !committedState.equals(snapshot)) {
                throw new IllegalStateException("IDL state changed; review target/generated-resources/META-INF/pipeline/idl.json and promote it to "
                    + statePath);
            }
            String baseline = resolveCompatibilityBaseline();
            if (baseline != null && !baseline.isBlank()) {
                PipelineIdlSnapshot baselineSnapshot = readBaselineSnapshot(baseline);
                List<String> errors = new PipelineIdlCompatibilityChecker().compare(baselineSnapshot, snapshot);
                if (!errors.isEmpty()) {
                    throw new IllegalStateException("IDL compatibility check failed:\n - " + String.join("\n - ", errors));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write IDL snapshot", e);
        }
    }

    private void writeIdlLock(Path statePath, PipelineIdlSnapshot snapshot) throws IOException {
        Files.createDirectories(statePath.getParent());
        Path temporaryStatePath = Files.createTempFile(statePath.getParent(), statePath.getFileName().toString(), ".tmp");
        try {
            IDL_MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporaryStatePath.toFile(), snapshot);
            Files.move(temporaryStatePath, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryStatePath);
        }
    }

    PipelineIdlSnapshot readBaselineSnapshot(String baseline) {
        try {
            return IDL_MAPPER.readValue(Path.of(baseline).toFile(), PipelineIdlSnapshot.class);
        } catch (InvalidPathException | IOException | SecurityException e) {
            throw new IllegalStateException("Invalid IDL compatibility baseline path '" + baseline + "'", e);
        }
    }

    private String resolveCompatibilityBaseline() {
        String value = System.getProperty(IDL_SNAPSHOT_PROPERTY);
        if (value == null || value.isBlank()) {
            value = System.getenv(IDL_SNAPSHOT_ENV);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }
}
