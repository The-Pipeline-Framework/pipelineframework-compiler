/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pipelineframework.compiler.polyglot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.orchestrator.release.PipelineContractDescriptor;
import org.pipelineframework.processor.util.PipelineContractMetadataGenerator;

/** Packages the Contract and the exact projection bytes using the Compiled Truth resource layout. */
final class NativeWorkerMetadata {
    private static final String PROJECTION_RESOURCE = "META-INF/pipeline/native-execution-projection.json";
    private static final LocalDateTime ARCHIVE_TIMESTAMP = LocalDateTime.of(1980, 1, 2, 0, 0);

    Map<String, byte[]> render(
        PipelineTemplateConfig model, LocalProfile.Projection projection, byte[] projectionBytes
    ) throws IOException {
        requireExactBoundaries(projection);
        byte[] contract = new PipelineContractMetadataGenerator().renderNativeWorkerContract(model,
            projection.profile(), projection.steps().stream().map(LocalProfile.Step::retryLimit).toList())
            .getBytes(StandardCharsets.UTF_8);
        var archive = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(archive)) {
            write(zip, PROJECTION_RESOURCE, projectionBytes);
            write(zip, PipelineContractDescriptor.RESOURCE_PATH, contract);
        }
        return Map.of("pipeline-contract.json", contract, "compiled-truth.zip", archive.toByteArray());
    }

    private void requireExactBoundaries(LocalProfile.Projection projection) {
        String previousOutput = projection.pipeline().inputType();
        for (var step : projection.steps()) {
            if (!previousOutput.equals(step.inputType())) {
                throw new IllegalArgumentException("Native worker step " + step.id() + " requires exact input type "
                    + previousOutput + ", found " + step.inputType());
            }
            previousOutput = step.outputType();
        }
        if (!previousOutput.equals(projection.pipeline().outputType())) {
            throw new IllegalArgumentException("Native worker requires exact pipeline output type " + previousOutput
                + ", found " + projection.pipeline().outputType());
        }
    }

    private void write(ZipOutputStream zip, String resource, byte[] bytes) throws IOException {
        var entry = new ZipEntry(resource);
        entry.setTimeLocal(ARCHIVE_TIMESTAMP);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
