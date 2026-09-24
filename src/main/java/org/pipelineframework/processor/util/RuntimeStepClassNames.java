package org.pipelineframework.processor.util;

import java.util.Objects;
import java.util.Optional;

import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.PipelineTransport;

/** Resolves the concrete class identity used to execute a compiled pipeline step. */
public final class RuntimeStepClassNames {

    private RuntimeStepClassNames() {
    }

    public static String className(PipelineStepModel model, PipelineTransport transportMode) {
        return className(model, transportMode, false);
    }

    public static String className(
        PipelineStepModel model,
        PipelineTransport transportMode,
        boolean requireGeneratedClient
    ) {
        return className(model, transportMode, requireGeneratedClient, Optional.empty());
    }

    public static String className(
        PipelineStepModel model,
        PipelineTransport transportMode,
        boolean requireGeneratedClient,
        Optional<String> resolvedRuntimeClass
    ) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(resolvedRuntimeClass, "resolvedRuntimeClass must not be null");
        if (resolvedRuntimeClass.isPresent()) {
            return resolvedRuntimeClass.orElseThrow();
        }
        if (usesGeneratedClient(model, requireGeneratedClient, Optional.empty())) {
            return ClientStepClassNames.className(
                model,
                Objects.requireNonNullElse(transportMode, PipelineTransport.GRPC));
        }
        if (model.serviceClassName() != null) {
            return model.serviceClassName().canonicalName();
        }
        throw new IllegalStateException("No runtime class is available for pipeline step '"
            + model.serviceName() + "'.");
    }

    public static boolean usesGeneratedClient(PipelineStepModel model, boolean requireGeneratedClient) {
        return usesGeneratedClient(model, requireGeneratedClient, Optional.empty());
    }

    public static boolean usesGeneratedClient(
        PipelineStepModel model,
        boolean requireGeneratedClient,
        Optional<String> resolvedRuntimeClass
    ) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(resolvedRuntimeClass, "resolvedRuntimeClass must not be null");
        if (resolvedRuntimeClass.isPresent() && model.serviceClassName() != null) {
            return !resolvedRuntimeClass.orElseThrow().equals(model.serviceClassName().canonicalName());
        }
        return requireGeneratedClient
            || model.enabledTargets().contains(GenerationTarget.CLIENT_STEP)
            || model.enabledTargets().contains(GenerationTarget.REST_CLIENT_STEP)
            || model.enabledTargets().contains(GenerationTarget.DEFERRED_COMPLETION_STEP)
            || model.enabledTargets().contains(GenerationTarget.COMMAND_CLIENT_STEP)
            || model.enabledTargets().contains(GenerationTarget.QUERY_CLIENT_STEP)
            || model.enabledTargets().contains(GenerationTarget.DYNAMIC_OPERATION_CLIENT_STEP);
    }
}
