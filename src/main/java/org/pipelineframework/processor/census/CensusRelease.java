package org.pipelineframework.processor.census;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/** Deployment gate shared by the packaged build integration and its sender. */
final class CensusRelease {
    static final URI ENDPOINT = URI.create("https://pipelineframework.org/api/census/v1/observations");
    static final boolean ENABLED = approved();
    private static final String METADATA = "META-INF/tpf/census-release.properties";

    private CensusRelease() { }

    /** The public destination is fixed. An explicit loopback address is available for local verification. */
    static Optional<URI> endpoint(String local) {
        if (local == null) return ENABLED ? Optional.of(ENDPOINT) : Optional.empty();
        try {
            URI uri = URI.create(local);
            if ("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0
                    && uri.getUserInfo() == null && uri.getFragment() == null) return Optional.of(uri);
        } catch (IllegalArgumentException unavailable) {
            // Fail closed if the local endpoint is malformed.
        }
        return Optional.empty();
    }

    /** Reads the release marker from the owning artifact rather than the application classpath. */
    private static boolean approved() {
        try {
            var source = CensusRelease.class.getProtectionDomain().getCodeSource();
            if (source == null || !"file".equals(source.getLocation().getProtocol())) return false;
            var artifact = Path.of(source.getLocation().toURI());
            if (Files.isDirectory(artifact)) {
                try (var input = Files.newInputStream(artifact.resolve(METADATA))) { return approved(input); }
            }
            try (var jar = new java.util.jar.JarFile(artifact.toFile())) {
                var entry = jar.getJarEntry(METADATA);
                if (entry == null) return false;
                try (var input = jar.getInputStream(entry)) { return approved(input); }
            }
        } catch (Exception unavailable) {
            return false;
        }
    }

    /** Requires an explicit true value to enable public reporting. */
    private static boolean approved(InputStream input) throws java.io.IOException {
        var properties = new Properties();
        properties.load(input);
        return "true".equals(properties.getProperty("public_reporting"));
    }
}
