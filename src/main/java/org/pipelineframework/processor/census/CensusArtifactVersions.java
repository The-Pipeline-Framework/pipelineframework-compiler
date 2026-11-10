package org.pipelineframework.processor.census;

/** Version metadata resolved in the compiler's own class loader. */
record CensusArtifactVersions(String compiler, String contracts, int javaMajor) {
    private static final class Current {
        private static final CensusArtifactVersions VALUE = new CensusArtifactVersions(
                CensusEvent.packagedVersion(CensusEvent.class, "/META-INF/tpf/compiler-version.properties"),
                CensusEvent.packagedVersion(org.pipelineframework.annotation.PipelineStep.class, "/META-INF/tpf/contracts-version.properties"),
                Runtime.version().feature());
    }

    /** Reads immutable artifact metadata once per compiler class loader. */
    static CensusArtifactVersions current() {
        return Current.VALUE;
    }
}
