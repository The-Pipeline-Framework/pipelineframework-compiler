package org.pipelineframework.processor.census;

import java.nio.file.*;
import java.util.*;

/** Shared build-frontend policy. The release artifact is preview-only until public rollout is approved. */
public record CensusConfiguration(Mode mode, Path stateDirectory, Optional<Path> previewFile, Optional<Path> hostSocket) {
    public enum Mode { OFF, PREVIEW, OUTBOX, REPORT }
    public CensusConfiguration(Mode mode, Path stateDirectory, Optional<Path> previewFile) {
        this(mode, stateDirectory, previewFile, Optional.empty());
    }
    public CensusConfiguration {
        Objects.requireNonNull(mode); Objects.requireNonNull(stateDirectory);
        Objects.requireNonNull(previewFile); Objects.requireNonNull(hostSocket);
    }
    /** Resolves opt-outs before preview or reporting and requires retained state on known disposable hosts. */
    public static CensusConfiguration resolve(Map<String, String> options, Map<String, String> environment, Properties properties) {
        return resolve(options, environment, properties, false);
    }

    /** Selects the default user state directory for an installed Maven integration. */
    static CensusConfiguration forMaven(Properties userProperties) {
        return resolve(Map.of("tpf.census.mode", "outbox", "tpf.census.enabled",
                userProperties.getProperty("tpf.census.enabled", "true")), System.getenv(), System.getProperties(), true);
    }

    private static CensusConfiguration resolve(Map<String, String> options, Map<String, String> environment,
            Properties properties, boolean mavenHost) {
        try {
            Path home = Path.of(properties.getProperty("user.home", "."));
            String os = properties.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            Path state = os.contains("mac") ? home.resolve("Library/Application Support/TPF/census")
                    : os.contains("win") ? Path.of(environment.getOrDefault("LOCALAPPDATA", home.toString())).resolve("TPF/census")
                    : Path.of(environment.getOrDefault("XDG_STATE_HOME", home.resolve(".local/state").toString())).resolve("tpf/census");
            if (environment.containsKey("TPF_CENSUS_STATE_DIR")) {
                String override = environment.get("TPF_CENSUS_STATE_DIR");
                if (override.isBlank()) return new CensusConfiguration(Mode.OFF, state, Optional.empty());
                state = Path.of(override);
            }
            boolean disabled = (options.containsKey("tpf.census.enabled")
                    && !"true".equalsIgnoreCase(options.get("tpf.census.enabled"))) || disables(environment.get("TPF_CENSUS_ENABLED"))
                    || disables(properties.getProperty("tpf.census.enabled"));
            String requested = options.getOrDefault("tpf.census.mode", environment.getOrDefault("TPF_CENSUS_MODE", "report"));
            boolean ephemeral = java.util.stream.Stream.of("CI", "GITHUB_ACTIONS", "JENKINS_URL", "HUDSON_URL",
                            "TEAMCITY_VERSION", "TF_BUILD", "GITLAB_CI", "BUILDKITE", "CIRCLECI", "TRAVIS",
                            "BITBUCKET_BUILD_NUMBER", "CODEBUILD_BUILD_ID", "BAMBOO_BUILDKEY")
                    .anyMatch(key -> enabled(environment.get(key)))
                    || environment.containsKey("container") || Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"));
            Mode mode = disabled || ephemeral && !"true".equalsIgnoreCase(environment.get("TPF_CENSUS_STATE_PERSISTENT"))
                    ? Mode.OFF : requested.equals("preview") ? Mode.PREVIEW
                    : requested.equals("outbox") && state.isAbsolute()
                            && (mavenHost || options.containsKey("tpf.census.hostSocket")
                            || environment.containsKey("TPF_CENSUS_STATE_DIR")) ? Mode.OUTBOX : Mode.OFF;
            Optional<Path> preview = Optional.ofNullable(options.getOrDefault("tpf.census.preview", environment.get("TPF_CENSUS_PREVIEW"))).map(Path::of);
            if (mode == Mode.PREVIEW && preview.isEmpty()) mode = Mode.OFF;
            Optional<Path> hostSocket = Optional.empty();
            if (mode == Mode.OUTBOX) {
                String socket = options.getOrDefault("tpf.census.hostSocket", environment.get("TPF_CENSUS_HOST_SOCKET"));
                if (socket == null && options.containsKey("tpf.census.hostSocket"))
                    return new CensusConfiguration(Mode.OFF, state, Optional.empty());
                hostSocket = Optional.ofNullable(socket).map(Path::of);
            }
            return new CensusConfiguration(mode, state, preview, hostSocket);
        } catch (RuntimeException invalid) {
            return new CensusConfiguration(Mode.OFF, Path.of("."), Optional.empty());
        }
    }
    /** Treats false or an invalid value as an opt-out when a gate is supplied. */
    private static boolean disables(String value) { return value != null && !"true".equalsIgnoreCase(value); }
    /** Recognizes a nonblank CI marker unless it is explicitly false. */
    private static boolean enabled(String value) { return value != null && !value.isBlank() && !"false".equalsIgnoreCase(value); }
}
