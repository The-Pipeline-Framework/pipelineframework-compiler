/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pipelineframework.compiler.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Compiler entrypoint for the bounded Node LOCAL application binding. */
public final class NodeLocalCompiler {
    private NodeLocalCompiler() {
    }

    /**
     * Generates an application binding from an explicit v3 YAML file and native handler bindings.
     * The output directory must be absent or empty. Inputs are read once and the complete output
     * set is published together.
     *
     * @throws IllegalArgumentException if the inputs cannot be compiled for this profile
     * @throws IOException if the inputs cannot be read or the output cannot be published
     */
    public static void generate(Path pipeline, Path bindings, Path output) throws IOException {
        Path destination = output.toAbsolutePath().normalize();
        requireEmptyDestination(destination);
        byte[] pipelineBytes = Files.readAllBytes(pipeline);
        byte[] bindingBytes = Files.readAllBytes(bindings);
        var profile = new NodeLocalProfile();
        var model = profile.load(pipeline, pipelineBytes);
        var projection = profile.project(model, pipeline, bindings, bindingBytes,
            new NodeLocalProfile.InputDigests(sha256(pipelineBytes), sha256(bindingBytes)));
        publish(destination, new NodeLocalTypeScript().render(projection));
    }

    /** Usage: {@code NodeLocalCompiler <pipeline.yaml> <native-bindings.json> <output-directory>}. */
    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: NodeLocalCompiler <pipeline.yaml> <native-bindings.json> <output-directory>");
            System.exit(2);
        }
        try {
            generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        } catch (IllegalArgumentException | IOException failure) {
            System.err.println("Node LOCAL generation failed: " + failure.getMessage());
            System.exit(2);
        }
    }

    static String sha256(byte[] bytes) {
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
        Path stage = Files.createTempDirectory(parent, ".tpf-node-");
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
