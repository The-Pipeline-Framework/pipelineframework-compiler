package org.pipelineframework.processor.census;

import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/** One-shot build-time sender for the approved fixed endpoint or a literal loopback test. */
public final class LocalCensusSender {
    private LocalCensusSender() { }

    /** Starts from Maven's session-end callback, independently of the lifetime of its JVM. */
    static void launchForMaven(Path state, String classpath, URI endpoint) throws Exception {
        if (!allowed(endpoint) || !state.isAbsolute()) return;
        // Reap the small launcher independently. The sender is not a child Maven must retain.
        // Pass every argument positionally, without interpolating paths into shell code.
        new ProcessBuilder("/bin/sh", "-c", "\"$@\" &", "tpf-census-launch",
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, LocalCensusSender.class.getName(), state.toString(), endpoint.toString())
                .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** Drains a single bounded batch. Concurrent helper processes never send from the same installation. */
    public static void main(String[] args) {
        if (args.length != 2) return;
        try {
            Path state = Path.of(args[0]);
            URI endpoint = URI.create(args[1]);
            if (!allowed(endpoint) || !state.isAbsolute() || Files.isSymbolicLink(state)
                    || !Files.isRegularFile(state.resolve("state.json"), LinkOption.NOFOLLOW_LINKS)) return;
            Path lockPath = state.resolve("sender.lock");
            try (var channel = FileChannel.open(lockPath,
                    java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    var lock = channel.tryLock()) {
                if (lock == null) return;
                var configuration = new CensusConfiguration(CensusConfiguration.Mode.OUTBOX, state, Optional.empty());
                BuildCensusOutbox.drain(configuration, new HttpsCensusTransport(endpoint));
            }
        } catch (Exception unavailable) {
            // The retained outbox is available to a later sender.
        }
    }

    /** Limits delivery to the approved endpoint and controlled loopback probes. */
    private static boolean allowed(URI endpoint) {
        return CensusRelease.endpoint(endpoint.toString()).isPresent()
                || CensusRelease.ENABLED && CensusRelease.ENDPOINT.equals(endpoint);
    }
}
