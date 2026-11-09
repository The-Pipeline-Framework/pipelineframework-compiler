package org.pipelineframework.processor.census;

/** Immutable completion handoff shared by compiler hosts; the host context stays local. */
public record BuildCensusObservation(BuildCensusHostContext host, BuildCensusSummary summary) {
    public BuildCensusObservation {
        java.util.Objects.requireNonNull(host); java.util.Objects.requireNonNull(summary);
    }
}
