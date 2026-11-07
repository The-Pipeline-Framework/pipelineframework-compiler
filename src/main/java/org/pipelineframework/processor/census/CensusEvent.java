package org.pipelineframework.processor.census;

import com.google.gson.Gson;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;

/** Serializes only explicit census values; never accepts an application model. */
public record CensusEvent(UUID installationId, UUID applicationInstanceId, LocalDate observedAt,
        String compilerVersion, String contractsVersion, int javaMajor, BuildCensusSummary summary) {
    public CensusEvent {
        Objects.requireNonNull(installationId); Objects.requireNonNull(applicationInstanceId); Objects.requireNonNull(observedAt);
        Objects.requireNonNull(summary);
        compilerVersion = normalizeVersion(compilerVersion); contractsVersion = normalizeVersion(contractsVersion);
        if (javaMajor < 1 || javaMajor > 999) throw new IllegalArgumentException("Invalid Java major");
    }
    public Map<String, Object> values() {
        Map<String, Object> result = new TreeMap<>(summary.projection());
        result.put("schema_version", 1); result.put("installation_id", installationId.toString());
        result.put("application_instance_id", applicationInstanceId.toString()); result.put("observed_at", observedAt.toString());
        result.put("compiler_version", compilerVersion); result.put("contracts_version", contractsVersion); result.put("java_major", javaMajor);
        return result;
    }
    public String json() { return new Gson().toJson(values()); }
    public String reportValues() {
        Map<String, Object> result = new TreeMap<>(summary.projection());
        result.put("compiler_version", compilerVersion); result.put("contracts_version", contractsVersion); result.put("java_major", javaMajor);
        return new Gson().toJson(result);
    }
    public static String normalizeVersion(String version) {
        if (version != null && version.matches("[0-9]{1,4}([.][0-9]{1,4}){1,3}(-SNAPSHOT|-dev)?"))
            return version.replace("-SNAPSHOT", "-dev");
        return "unknown";
    }
    public static String packagedVersion(Class<?> owner, String resource) {
        // Resolve beside the owning class artifact, never through the global resource namespace.
        try {
            var source = owner.getProtectionDomain().getCodeSource();
            if (source == null || !source.getLocation().getProtocol().equals("file")) return "unknown";
            var artifact = java.nio.file.Path.of(source.getLocation().toURI());
            String name = resource.startsWith("/") ? resource.substring(1) : resource;
            if (java.nio.file.Files.isDirectory(artifact)) {
                var file = artifact.resolve(name).normalize();
                if (!file.startsWith(artifact)) return "unknown";
                try (InputStream input = java.nio.file.Files.newInputStream(file)) { return readVersion(input); }
            }
            try (var jar = new java.util.jar.JarFile(artifact.toFile())) {
                var entry = jar.getJarEntry(name);
                if (entry == null) return "unknown";
                try (InputStream input = jar.getInputStream(entry)) { return readVersion(input); }
            }
        } catch (Exception unavailable) { return "unknown"; }
    }
    private static String readVersion(InputStream input) throws java.io.IOException {
        Properties values = new Properties(); values.load(input);
        return normalizeVersion(values.getProperty("version"));
    }
}
