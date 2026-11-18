/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pipelineframework.compiler.polyglot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared compilation path invoked by the experimental native LOCAL entrypoints. */
public final class NativeLocalCompiler {
    public enum Target {
        NODE("compiler-node-local"),
        PYTHON("compiler-python-local");

        private final String kind;

        Target(String kind) {
            this.kind = kind;
        }
    }

    private NativeLocalCompiler() {
    }

    public static void generate(Target target, Path pipeline, Path bindings, Path output) throws IOException {
        Path destination = output.toAbsolutePath().normalize();
        requireEmptyDestination(destination);
        byte[] pipelineBytes = Files.readAllBytes(pipeline);
        byte[] bindingBytes = Files.readAllBytes(bindings);
        var profile = new LocalProfile();
        var model = profile.load(pipeline, pipelineBytes);
        var projection = profile.project(model, pipeline, bindings, bindingBytes,
            new LocalProfile.InputDigests(sha256(pipelineBytes), sha256(bindingBytes)), target.kind);

        String projectionJson = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(projection)
            + "\n";
        String digest = sha256(projectionJson.getBytes(StandardCharsets.UTF_8));
        var outputs = new LinkedHashMap<>(switch (target) {
            case NODE -> new NodeLocalTypeScript().render(projection, pipeline, projectionJson, digest);
            case PYTHON -> new PythonLocalSource().render(projection, pipeline, projectionJson, digest);
        });
        outputs.put("projection.json", projectionJson);
        publish(destination, outputs);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java must provide SHA-256", impossible);
        }
    }

    private static void requireEmptyDestination(Path destination) throws IOException {
        if (Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(destination)) {
                if (entries.findAny().isEmpty()) {
                    return;
                }
            }
        }
        throw new IOException(destination + ": output directory must be absent or empty");
    }

    private static void publish(Path destination, Map<String, String> outputs) throws IOException {
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path stage = Files.createTempDirectory(parent, ".tpf-local-");
        try {
            for (var output : outputs.entrySet()) {
                Files.writeString(stage.resolve(output.getKey()), output.getValue());
            }
            requireEmptyDestination(destination);
            Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                for (String name : outputs.keySet()) {
                    Files.deleteIfExists(stage.resolve(name));
                }
                Files.deleteIfExists(stage);
            }
        }
    }
}
