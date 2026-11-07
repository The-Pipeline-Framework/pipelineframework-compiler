package org.pipelineframework.processor.census;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** The entire serializable compiler census vocabulary. Never contains a semantic model or source identity. */
public record BuildCensusSummary(Map<String, CountBand> sizes, Map<String, CountBand> featureCounts,
        Transport transport, Platform platform, Layout runtimeLayout) {
    public enum Transport { LOCAL, REST, GRPC }
    public enum Platform { COMPUTE, FUNCTION }
    public enum Layout { MODULAR, PIPELINE_RUNTIME, MONOLITH, UNSPECIFIED }
    public BuildCensusSummary {
        sizes = checked(sizes, Set.of("pipelines", "steps", "types", "blocks"));
        featureCounts = checked(featureCounts, Set.of("nested_invocation", "one_to_many", "many_to_one", "query", "command", "await"));
        Objects.requireNonNull(transport); Objects.requireNonNull(platform); Objects.requireNonNull(runtimeLayout);
    }
    private static Map<String, CountBand> checked(Map<String, CountBand> values, Set<String> keys) {
        if (!values.keySet().equals(keys)) throw new IllegalArgumentException("Unexpected census vocabulary");
        values.values().forEach(Objects::requireNonNull);
        return Map.copyOf(values);
    }
    public Map<String, Object> projection() {
        Map<String, Object> result = new TreeMap<>();
        result.put("sizes", bands(sizes)); result.put("feature_counts", bands(featureCounts));
        result.put("transport", transport.name()); result.put("platform", platform.name());
        result.put("runtime_layout", runtimeLayout.name());
        return result;
    }
    private Map<String, String> bands(Map<String, CountBand> counts) {
        Map<String, String> result = new TreeMap<>();
        counts.forEach((key, band) -> result.put(key, band.value()));
        return result;
    }
}
