package org.pipelineframework.processor.util;

import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.PipelineTransport;

/** Resolves the generated runtime step class selected by the compiler for a step model. */
public final class ClientStepClassNames {

    private ClientStepClassNames() {
    }

    public static String className(PipelineStepModel model, PipelineTransport transportMode) {
        return model.servicePackage() + ".pipeline."
            + stripTrailingService(model.generatedName())
            + suffix(model, transportMode.clientStepSuffix());
    }

    /** Runtime class for the operation before its optional completion decorator. */
    public static String operationClassName(PipelineStepModel model, PipelineTransport transport) {
        String suffix = model.enabledTargets().contains(GenerationTarget.COMMAND_CLIENT_STEP) ? "CommandClientStep"
            : model.enabledTargets().contains(GenerationTarget.QUERY_CLIENT_STEP) ? "QueryClientStep"
            : transport.clientStepSuffix();
        return model.servicePackage() + ".pipeline." + stripTrailingService(model.generatedName()) + suffix;
    }

    public static String suffix(PipelineStepModel model, String defaultSuffix) {
        if (model.enabledTargets().contains(GenerationTarget.DEFERRED_COMPLETION_STEP)) {
            return "DeferredCompletionStep";
        }
        if (model.enabledTargets().contains(GenerationTarget.COMMAND_CLIENT_STEP)) {
            return "CommandClientStep";
        }
        if (model.enabledTargets().contains(GenerationTarget.QUERY_CLIENT_STEP)) {
            return "QueryClientStep";
        }
        if (model.enabledTargets().contains(GenerationTarget.DYNAMIC_OPERATION_CLIENT_STEP)) {
            return "DynamicOperationClientStep";
        }
        return defaultSuffix;
    }

    public static String stripTrailingService(String generatedName) {
        if (generatedName == null) {
            return "";
        }
        return generatedName.endsWith("Service")
            ? generatedName.substring(0, generatedName.length() - "Service".length())
            : generatedName;
    }
}
