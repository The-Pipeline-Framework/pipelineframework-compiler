package org.pipelineframework.processor.census;

import java.nio.file.*;
import java.util.*;

/** Local-only host data. Must never enter the event serializer. */
public record BuildCensusHostContext(Path originalConfiguration, CensusConfiguration configuration, Set<Path> packagedRoots) {
    public BuildCensusHostContext(Path originalConfiguration, CensusConfiguration configuration) {
        this(originalConfiguration, configuration, Set.of());
    }
    public BuildCensusHostContext {
        Objects.requireNonNull(originalConfiguration); Objects.requireNonNull(configuration);
        packagedRoots = Set.copyOf(packagedRoots);
    }
    /** Worker-only filesystem check; inspection must never write into source or packaged output. */
    public boolean permitsPreviewOutput() throws java.io.IOException {
        Path output = configuration.previewFile().orElseThrow();
        if (!output.isAbsolute() || Files.isSymbolicLink(output)) return false;
        Path resolved = output.getParent().toRealPath().resolve(output.getFileName()).normalize();
        if (resolved.equals(originalConfiguration.toRealPath())) return false;
        Path state = configuration.stateDirectory().toAbsolutePath().normalize();
        if (Files.exists(state)) state = state.toRealPath();
        if (resolved.equals(state.resolve("state.json")) || resolved.equals(state.resolve("state.lock"))) return false;
        Set<String> reserved = Set.of("src", "resources", "classes", "test-classes", "target", "build",
                "generated-sources", "generated-test-sources", "META-INF");
        for (Path part : resolved) if (reserved.contains(part.toString())) return false;
        for (Path root : packagedRoots) {
            Path path = root.toAbsolutePath().normalize();
            Path existing = path;
            while (!Files.exists(existing)) existing = existing.getParent();
            Path canonical = existing.toRealPath().resolve(existing.relativize(path)).normalize();
            if (resolved.startsWith(canonical)) return false;
        }
        return true;
    }
}
