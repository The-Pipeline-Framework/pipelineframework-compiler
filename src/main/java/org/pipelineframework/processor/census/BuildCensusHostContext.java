package org.pipelineframework.processor.census;

import java.nio.file.*;
import java.util.*;

/** Local-only host data. Must never enter the event serializer. */
public record BuildCensusHostContext(Path originalConfiguration, CensusConfiguration configuration, Set<Path> packagedRoots) {
    private static final Set<String> RESERVED = Set.of("src", "resources", "classes", "test-classes", "target", "build",
            "generated-sources", "generated-test-sources", "META-INF");
    public BuildCensusHostContext(Path originalConfiguration, CensusConfiguration configuration) {
        this(originalConfiguration, configuration, Set.of());
    }
    public BuildCensusHostContext {
        Objects.requireNonNull(originalConfiguration); Objects.requireNonNull(configuration);
        packagedRoots = Set.copyOf(packagedRoots);
    }
    /** Worker-only filesystem check; inspection must never write into source or packaged output. */
    public Optional<Path> previewDestination() throws java.io.IOException {
        Path output = configuration.previewFile().orElseThrow();
        if (!output.isAbsolute() || Files.isSymbolicLink(output)) return Optional.empty();
        Path resolved = output.getParent().toRealPath().resolve(output.getFileName()).normalize();
        if (resolved.equals(originalConfiguration.toRealPath())) return Optional.empty();
        Path state = configuration.stateDirectory().toAbsolutePath().normalize();
        if (Files.exists(state)) state = state.toRealPath();
        if (resolved.equals(state.resolve("state.json")) || resolved.equals(state.resolve("state.lock"))) return Optional.empty();
        for (Path part : resolved) if (RESERVED.contains(part.toString())) return Optional.empty();
        if (inPackagedOutput(resolved)) return Optional.empty();
        return Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) ? Optional.empty() : Optional.of(resolved);
    }

    /** Resolves the private outbox directory without allowing source or packaged output. */
    Optional<Path> outboxDirectory() throws java.io.IOException {
        Path path = configuration.stateDirectory().normalize();
        if (!path.isAbsolute() || Files.isSymbolicLink(path)) return Optional.empty();
        Path resolved = canonical(path);
        Path anchor = originalConfiguration.toRealPath();
        if (anchor.startsWith(resolved) || inPackagedOutput(resolved)) return Optional.empty();
        Path project = anchor.getParent();
        if (resolved.startsWith(project)) {
            for (Path part : project.relativize(resolved)) {
                if (RESERVED.contains(part.toString())) return Optional.empty();
            }
        }
        return Optional.of(resolved);
    }

    /** Checks canonical compiler-owned roots, including custom output directories. */
    private boolean inPackagedOutput(Path resolved) throws java.io.IOException {
        for (Path root : packagedRoots) {
            if (resolved.startsWith(canonical(root.toAbsolutePath().normalize()))) return true;
        }
        return false;
    }

    /** Resolves existing ancestors while allowing a new directory hierarchy. */
    private Path canonical(Path path) throws java.io.IOException {
        Path existing = path;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        return existing.toRealPath().resolve(existing.relativize(path)).normalize();
    }
}
