package org.pipelineframework.processor.ir;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.squareup.javapoet.ClassName;

/** Canonical projection for a selected operation-output branch and its continuing outcomes. */
public record AwaitBranchSelection(
    String requestCanonicalType,
    ClassName requestType,
    String completionCanonicalType,
    ClassName completionType,
    CaseAccess selectedInput,
    Optional<ClassName> completionWrapper,
    List<Bypass> bypasses
) {
    public AwaitBranchSelection {
        Objects.requireNonNull(requestCanonicalType, "requestCanonicalType");
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(completionCanonicalType, "completionCanonicalType");
        Objects.requireNonNull(completionType, "completionType");
        Objects.requireNonNull(selectedInput, "selectedInput");
        Objects.requireNonNull(completionWrapper, "completionWrapper");
        bypasses = List.copyOf(bypasses);
    }

    public record CaseAccess(ClassName type, String accessor) {
        public CaseAccess {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(accessor, "accessor");
        }
    }

    public record Bypass(CaseAccess input, Optional<ClassName> outputWrapper) {
        public Bypass {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(outputWrapper, "outputWrapper");
        }
    }
}
