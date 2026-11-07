package org.pipelineframework.processor.census;

import java.nio.file.*;
import java.util.*;

/** Shared build-frontend policy. The release artifact is preview-only until public rollout is approved. */
public record CensusConfiguration(Mode mode, Path stateDirectory, Optional<Path> previewFile) {
    public enum Mode { OFF, PREVIEW, REPORT }
    public static final boolean PUBLIC_REPORTING_RELEASED = false;
    public CensusConfiguration { Objects.requireNonNull(mode); Objects.requireNonNull(stateDirectory); Objects.requireNonNull(previewFile); }
    public static CensusConfiguration resolve(Map<String, String> options, Map<String, String> environment, Properties properties) {
        try {
            Path home = Path.of(properties.getProperty("user.home", "."));
            String os = properties.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            Path state = os.contains("mac") ? home.resolve("Library/Application Support/TPF/census")
                    : os.contains("win") ? Path.of(environment.getOrDefault("LOCALAPPDATA", home.toString())).resolve("TPF/census")
                    : Path.of(environment.getOrDefault("XDG_STATE_HOME", home.resolve(".local/state").toString())).resolve("tpf/census");
            if (environment.containsKey("TPF_TELEMETRY_STATE_DIR")) state = Path.of(environment.get("TPF_TELEMETRY_STATE_DIR"));
            boolean disabled = disables(options.get("tpf.telemetry.disabled")) || disables(environment.get("TPF_TELEMETRY_DISABLED"))
                    || disables(properties.getProperty("tpf.telemetry.disabled"));
            String requested = options.getOrDefault("tpf.telemetry.mode", environment.getOrDefault("TPF_TELEMETRY_MODE", "report"));
            boolean ephemeral = java.util.stream.Stream.of("CI", "GITHUB_ACTIONS", "JENKINS_URL", "HUDSON_URL",
                            "TEAMCITY_VERSION", "TF_BUILD", "GITLAB_CI", "BUILDKITE", "CIRCLECI", "TRAVIS",
                            "BITBUCKET_BUILD_NUMBER", "CODEBUILD_BUILD_ID", "BAMBOO_BUILDKEY")
                    .anyMatch(key -> enabled(environment.get(key)))
                    || environment.containsKey("container") || Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"));
            Mode mode = disabled || ephemeral && !"true".equalsIgnoreCase(environment.get("TPF_TELEMETRY_STATE_PERSISTENT"))
                    ? Mode.OFF : requested.equals("preview") ? Mode.PREVIEW
                    : requested.equals("report") && PUBLIC_REPORTING_RELEASED ? Mode.REPORT : Mode.OFF;
            Optional<Path> preview = Optional.ofNullable(options.getOrDefault("tpf.telemetry.preview", environment.get("TPF_TELEMETRY_PREVIEW"))).map(Path::of);
            if (mode == Mode.PREVIEW && preview.isEmpty()) mode = Mode.OFF;
            return new CensusConfiguration(mode, state, preview);
        } catch (RuntimeException invalid) {
            return new CensusConfiguration(Mode.OFF, Path.of("."), Optional.empty());
        }
    }
    private static boolean disables(String value) { return value != null && !"false".equalsIgnoreCase(value); }
    private static boolean enabled(String value) { return value != null && !value.isBlank() && !"false".equalsIgnoreCase(value); }
}
