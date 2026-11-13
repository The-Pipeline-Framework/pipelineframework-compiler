package org.pipelineframework.processor.renderer;

import com.squareup.javapoet.TypeName;
import java.util.LinkedHashMap;
import java.util.Map;
import org.pipelineframework.processor.ir.PipelineStepModel;

/** Value-only facts captured from the descriptor arguments selected during rendering. */
final class AwaitRuntimeDescriptorMetadata {
    private AwaitRuntimeDescriptorMetadata() { }

    static Map<String, Object> ordinary(PipelineStepModel model, TypeName input, TypeName output,
                                       TypeName transportInput, TypeName transportOutput) {
        var completion = model.deferredCompletionSelection().orElseThrow();
        var value = base(model, input, output, transportInput, transportOutput);
        value.put("canonicalInputTypeId", completion.branchSelection().map(branch -> branch.requestCanonicalType())
            .orElseGet(() -> model.outputMapping().canonicalTypeName().orElse(input.toString())));
        value.put("canonicalOutputTypeId", completion.branchSelection().map(branch -> branch.completionCanonicalType())
            .orElse(completion.finalOutputCanonicalType()));
        value.put("acknowledgementAwareCommandCompletion", false);
        return java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(value));
    }

    static Map<String, Object> command(PipelineStepModel model) {
        var completion = model.deferredCompletionSelection().orElseThrow();
        var value = base(model, model.inboundDomainType(), completion.finalOutputType(),
            model.inboundDomainType(), completion.completionPayloadType().orElseThrow());
        value.put("canonicalInputTypeId", model.inputMapping().canonicalTypeName()
            .orElse(model.inboundDomainType().toString()));
        value.put("canonicalOutputTypeId", completion.finalOutputCanonicalType());
        value.put("acknowledgementAwareCommandCompletion", completion.acknowledgementAwareCompletion());
        if (completion.acknowledgementAwareCompletion()) {
            value.put("commandAcknowledgementTypeId", model.outboundDomainType().toString());
            value.put("canonicalCommandAcknowledgementTypeId", model.outputMapping().canonicalTypeName()
                .orElse(model.outboundDomainType().toString()));
        }
        var callback = completion.callback().orElseThrow();
        var selected = callback.operation();
        value.put("callbackContract", java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(Map.of(
            "binding", selected.binding().value(), "provider", selected.operation().providerId().value(),
            "providerVersion", selected.providerMajorVersion(), "operation", selected.operation().operationId(),
            "operationVersion", selected.operation().majorVersion(), "callback", callback.descriptor().id(),
            "payloadType", callback.descriptor().typeContract().inputType(),
            "endpointResolver", callback.endpointResolver().canonicalName(),
            "authenticator", callback.authenticator().canonicalName(), "required", callback.descriptor().required()))));
        return java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(value));
    }

    private static Map<String, Object> base(PipelineStepModel model, TypeName input, TypeName output,
                                           TypeName transportInput, TypeName transportOutput) {
        var completion = model.deferredCompletionSelection().orElseThrow();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("stepId", model.serviceName());
        value.put("inputTypeId", input.toString());
        value.put("outputTypeId", output.toString());
        value.put("transportInputTypeId", transportInput.toString());
        value.put("transportOutputTypeId", transportOutput.toString());
        value.put("completionPayloadTypeId", completion.completionPayloadCanonicalType()
            .orElseGet(() -> completion.completionPayloadType().map(TypeName::toString)
                .orElseGet(() -> completion.branchSelection().map(branch -> branch.completionCanonicalType())
                    .orElse(completion.finalOutputCanonicalType()))));
        value.put("requestAwareCompletion", completion.completionProjector().isPresent());
        completion.completionProjector().ifPresent(projector -> value.put("completionProjectorId", projector.canonicalName()));
        return value;
    }
}
