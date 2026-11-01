package org.pipelineframework.processor.ir;

import java.util.Objects;
import java.util.Optional;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;

/** An observer's authored owner and optional union extraction; the pipeline value is preserved. */
public record ObservationProjection(TypeName carrierType, Optional<Variant> variant, String ownerServiceName) {
    public ObservationProjection(TypeName carrierType, ClassName variantType, String accessor, String ownerServiceName) {
        this(carrierType, Optional.of(new Variant(variantType, accessor)), ownerServiceName);
    }

    public ObservationProjection {
        Objects.requireNonNull(carrierType, "carrierType");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(ownerServiceName, "ownerServiceName");
        if (ownerServiceName.isBlank()) throw new IllegalArgumentException("Observation owner must not be blank");
    }

    public ClassName variantType() { return variant.orElseThrow().type(); }
    public String accessor() { return variant.orElseThrow().accessor(); }

    public record Variant(ClassName type, String accessor) {
        public Variant {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(accessor, "accessor");
            if (accessor.isBlank()) throw new IllegalArgumentException("Observation accessor must not be blank");
        }
    }
}
