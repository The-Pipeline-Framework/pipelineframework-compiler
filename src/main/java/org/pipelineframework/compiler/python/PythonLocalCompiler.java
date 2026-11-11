/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pipelineframework.compiler.python;

import java.io.IOException;
import java.nio.file.Path;
import org.pipelineframework.compiler.polyglot.NativeLocalCompiler;

/** Compiler entrypoint for the bounded Python LOCAL application binding. */
public final class PythonLocalCompiler {
    private PythonLocalCompiler() {
    }

    /**
     * Generates the Python application binding and its projection from explicit v3 YAML and native handler bindings.
     *
     * @throws IllegalArgumentException if the inputs cannot be compiled for this profile
     * @throws IOException if the inputs cannot be read or the output cannot be published
     */
    public static void generate(Path pipeline, Path bindings, Path output) throws IOException {
        NativeLocalCompiler.generate(NativeLocalCompiler.Target.PYTHON, pipeline, bindings, output);
    }

    /** Usage: {@code PythonLocalCompiler <pipeline.yaml> <native-bindings.json> <output-directory>}. */
    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: PythonLocalCompiler <pipeline.yaml> <native-bindings.json> <output-directory>");
            System.exit(2);
        }
        try {
            generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        } catch (IllegalArgumentException | IOException failure) {
            System.err.println("Python LOCAL generation failed: " + failure.getMessage());
            System.exit(2);
        }
    }
}
