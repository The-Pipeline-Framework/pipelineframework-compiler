package org.pipelineframework.processor.ir;

import java.util.Locale;

/** Mechanical host for QUEUE_ASYNC coordination; pipeline semantics remain provider-neutral. */
public enum CoordinationHost {
    NATIVE,
    AWS_DURABLE;

    public static CoordinationHost parse(String value) {
        if (value == null || value.isBlank()) {
            return NATIVE;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "NATIVE" -> NATIVE;
            case "AWS_DURABLE" -> AWS_DURABLE;
            default -> throw new IllegalArgumentException(
                "Unsupported pipeline.coordination.host '" + value + "'; expected NATIVE or AWS_DURABLE");
        };
    }
}
