package org.pipelineframework.processor.census;

/** Public coarse count vocabulary. Negative values mean the compiler cannot establish a count. */
public enum CountBand {
    ZERO("0"), ONE("1"), TWO_TO_FIVE("2–5"), SIX_TO_TEN("6–10"), ELEVEN_TO_TWENTY_FIVE("11–25"),
    TWENTY_SIX_TO_FIFTY("26–50"), FIFTY_ONE_TO_HUNDRED("51–100"), OVER_HUNDRED("101+"), UNKNOWN("unknown");
    private final String value;
    CountBand(String value) { this.value = value; }
    public String value() { return value; }
    /** Maps a count to the protocol band; negative input represents an unknown count. */
    public static CountBand of(long count) {
        if (count < 0) return UNKNOWN;
        if (count == 0) return ZERO;
        if (count == 1) return ONE;
        if (count <= 5) return TWO_TO_FIVE;
        if (count <= 10) return SIX_TO_TEN;
        if (count <= 25) return ELEVEN_TO_TWENTY_FIVE;
        if (count <= 50) return TWENTY_SIX_TO_FIFTY;
        if (count <= 100) return FIFTY_ONE_TO_HUNDRED;
        return OVER_HUNDRED;
    }
}
