/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pipelineframework.compiler.polyglot;

import java.io.IOException;
import java.nio.file.Path;

/** Opt-in generation for the bounded native whole-bundle worker profile. */
public final class NativeWorkerCompiler {
    private NativeWorkerCompiler() {
    }

    /**
     * Generates native application sources, the coordinator Contract, and the Compiled Truth carrier
     * from the same admitted inputs. The output directory must be absent or empty.
     */
    public static void generate(String target, Path pipeline, Path bindings, Path output) throws IOException {
        var language = switch (target) {
            case "node-worker" -> NativeLocalCompiler.Target.NODE;
            case "python-worker" -> NativeLocalCompiler.Target.PYTHON;
            default -> throw new IllegalArgumentException("Unknown native worker target: " + target);
        };
        NativeLocalCompiler.generate(language, pipeline, bindings, output, true);
    }

    /** Command-line entrypoint for the pinned remote generation host. */
    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: NativeWorkerCompiler <node-worker|python-worker> "
                + "<pipeline.yaml> <native-bindings.json> <output-directory>");
            System.exit(2);
        }
        try {
            generate(args[0], Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        } catch (IllegalArgumentException | IOException failure) {
            System.err.println("Native worker generation failed: " + failure.getMessage());
            System.exit(2);
        }
    }
}
