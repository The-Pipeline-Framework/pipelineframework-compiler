/*
 * Copyright (c) 2023-2026 Mariano Barcia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pipelineframework.processor.ir;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

import com.squareup.javapoet.ClassName;
import org.pipelineframework.config.template.PipelineTemplateStepExecution;

/** Immutable semantic definition of one authored pipeline operation. */
public record StepDefinition(
    String name,
    StepKind kind,
    @Nullable ClassName executionClass,
    Optional<String> delegatedMethodName,
    @Nullable PipelineTemplateStepExecution remoteExecution,
    @Nullable String command,
    @Nullable ClassName commandIdGenerator,
    @Nullable String duplicatePolicy,
    Map<String, Object> commandConfig,
    @Nullable String queryId,
    Map<String, Object> queryConfig,
    List<String> queryKeyFields,
    @Nullable ClassName inboundMapper,
    @Nullable ClassName outboundMapper,
    @Nullable ClassName externalMapper,
    MapperFallbackMode mapperFallback,
    @Nullable ClassName inputType,
    @Nullable ClassName outputType,
    @Nullable StreamingShape streamingShapeHint,
    boolean runOnVirtualThreads,
    List<String> accepts,
    boolean terminal,
    Optional<String> pipelineReference,
    Optional<String> dynamicOperationSource,
    Optional<ConnectorOperationSelection> connectorOperationSelection,
    Optional<DeferredCompletionDefinition> deferredCompletion,
    StepExecutionShape executionShape
) {
    public StepDefinition {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        delegatedMethodName = normalized(delegatedMethodName);
        pipelineReference = normalized(pipelineReference);
        dynamicOperationSource = normalized(dynamicOperationSource);
        Objects.requireNonNull(executionShape, "executionShape");
        if (executionShape.kind() != kind
            || (executionShape == StepExecutionShape.DYNAMIC_OPERATION) != dynamicOperationSource.isPresent()) {
            throw new IllegalArgumentException("executionShape does not match step semantics or binding provenance");
        }
        connectorOperationSelection = connectorOperationSelection == null ? Optional.empty() : connectorOperationSelection;
        deferredCompletion = deferredCompletion == null ? Optional.empty() : deferredCompletion;
        commandConfig = commandConfig == null ? Map.of() : Map.copyOf(commandConfig);
        queryConfig = queryConfig == null ? Map.of() : Map.copyOf(queryConfig);
        queryKeyFields = queryKeyFields == null ? List.of() : List.copyOf(queryKeyFields);
        accepts = accepts == null ? List.of() : List.copyOf(accepts);
        mapperFallback = mapperFallback == null ? MapperFallbackMode.NONE : mapperFallback;

        if (runOnVirtualThreads && (kind != StepKind.INTERNAL || dynamicOperationSource.isPresent())) {
            throw new IllegalArgumentException("runOnVirtualThreads is valid only for INTERNAL steps");
        }
        if (executionClass != null && remoteExecution != null) {
            throw new IllegalArgumentException("executionClass and remoteExecution are mutually exclusive");
        }
        deferredCompletion.ifPresent(completion -> {
            boolean callback = completion.callback().isPresent();
            if ((callback && kind != StepKind.COMMAND) || (!callback && kind != StepKind.INTERNAL)) {
                throw new IllegalArgumentException("deferred completion requires INTERNAL + transport or native COMMAND + callback");
            }
        });

        executionShape.validate(new StepExecutionShape.Contract(
            kind, executionClass, remoteExecution, inputType, outputType, pipelineReference,
            command, commandIdGenerator, queryId));
    }

    /** Compatibility constructor for compiler fixtures and callers that do not select a shape while parsing. */
    public StepDefinition(
        String name, StepKind kind, @Nullable ClassName executionClass, Optional<String> delegatedMethodName,
        @Nullable PipelineTemplateStepExecution remoteExecution, @Nullable String command,
        @Nullable ClassName commandIdGenerator, @Nullable String duplicatePolicy, Map<String, Object> commandConfig,
        @Nullable String queryId, Map<String, Object> queryConfig, List<String> queryKeyFields,
        @Nullable ClassName inboundMapper, @Nullable ClassName outboundMapper, @Nullable ClassName externalMapper,
        MapperFallbackMode mapperFallback, @Nullable ClassName inputType, @Nullable ClassName outputType,
        @Nullable StreamingShape streamingShapeHint, boolean runOnVirtualThreads, List<String> accepts,
        boolean terminal, Optional<String> pipelineReference, Optional<String> dynamicOperationSource,
        Optional<ConnectorOperationSelection> connectorOperationSelection,
        Optional<DeferredCompletionDefinition> deferredCompletion
    ) {
        this(name, kind, executionClass, delegatedMethodName, remoteExecution, command, commandIdGenerator,
            duplicatePolicy, commandConfig, queryId, queryConfig, queryKeyFields, inboundMapper, outboundMapper,
            externalMapper, mapperFallback, inputType, outputType, streamingShapeHint, runOnVirtualThreads,
            accepts, terminal, pipelineReference, dynamicOperationSource, connectorOperationSelection,
            deferredCompletion, StepExecutionShape.from(kind, normalized(dynamicOperationSource)));
    }

    public StepDefinition withExecutionShape(StepExecutionShape selectedShape) {
        return new StepDefinition(name, kind, executionClass, delegatedMethodName, remoteExecution,
            command, commandIdGenerator, duplicatePolicy, commandConfig, queryId, queryConfig, queryKeyFields,
            inboundMapper, outboundMapper, externalMapper, mapperFallback, inputType, outputType,
            streamingShapeHint, runOnVirtualThreads, accepts, terminal, pipelineReference, dynamicOperationSource,
            connectorOperationSelection, deferredCompletion, Objects.requireNonNull(selectedShape, "selectedShape"));
    }

    public static StepDefinition pipeline(
        String name,
        ClassName inputType,
        ClassName outputType,
        StreamingShape streamingShapeHint,
        List<String> accepts,
        boolean terminal,
        String pipelineReference
    ) {
        return new StepDefinition(name, StepKind.PIPELINE, null, Optional.empty(), null,
            null, null, null, Map.of(), null, Map.of(), List.of(), null, null, null,
            MapperFallbackMode.NONE, inputType, outputType, streamingShapeHint, false, accepts, terminal,
            Optional.ofNullable(pipelineReference), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public StepDefinition withConnectorOperationSelection(ConnectorOperationSelection selection) {
        return new StepDefinition(name, kind, executionClass, delegatedMethodName, remoteExecution,
            command, commandIdGenerator, duplicatePolicy, commandConfig, queryId, queryConfig, queryKeyFields,
            inboundMapper, outboundMapper, externalMapper, mapperFallback, inputType, outputType,
            streamingShapeHint, runOnVirtualThreads, accepts, terminal, pipelineReference, dynamicOperationSource,
            Optional.of(Objects.requireNonNull(selection, "selection")), deferredCompletion, executionShape);
    }

    public StepDefinition withDeferredCompletion(DeferredCompletionDefinition completion) {
        return new StepDefinition(name, kind, executionClass, delegatedMethodName, remoteExecution,
            command, commandIdGenerator, duplicatePolicy, commandConfig, queryId, queryConfig, queryKeyFields,
            inboundMapper, outboundMapper, externalMapper, mapperFallback, inputType, outputType,
            streamingShapeHint, runOnVirtualThreads, accepts, terminal, pipelineReference, dynamicOperationSource,
            connectorOperationSelection, Optional.of(Objects.requireNonNull(completion, "completion")), executionShape);
    }

    /** Convenience constructor for ordinary authored operations used by compiler tests. */
    public StepDefinition(
        String name,
        StepKind kind,
        ClassName executionClass,
        @Nullable ClassName externalMapper,
        MapperFallbackMode mapperFallback,
        @Nullable ClassName inputType,
        @Nullable ClassName outputType,
        @Nullable StreamingShape streamingShapeHint
    ) {
        this(name, kind, executionClass, null, null, externalMapper, mapperFallback,
            inputType, outputType, streamingShapeHint);
    }

    public StepDefinition(
        String name,
        StepKind kind,
        ClassName executionClass,
        @Nullable ClassName inboundMapper,
        @Nullable ClassName outboundMapper,
        MapperFallbackMode mapperFallback,
        @Nullable ClassName inputType,
        @Nullable ClassName outputType,
        @Nullable StreamingShape streamingShapeHint
    ) {
        this(name, kind, executionClass, inboundMapper, outboundMapper, null, mapperFallback,
            inputType, outputType, streamingShapeHint);
    }

    public StepDefinition(
        String name,
        StepKind kind,
        ClassName executionClass,
        @Nullable ClassName inboundMapper,
        @Nullable ClassName outboundMapper,
        @Nullable ClassName externalMapper,
        MapperFallbackMode mapperFallback,
        @Nullable ClassName inputType,
        @Nullable ClassName outputType,
        @Nullable StreamingShape streamingShapeHint
    ) {
        this(name, requireAuthoredKind(kind), executionClass, Optional.empty(), null,
            null, null, null, Map.of(), null, Map.of(), List.of(), inboundMapper, outboundMapper,
            externalMapper, mapperFallback, inputType, outputType, streamingShapeHint, false, List.of(), false,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public StepDefinition(
        String name,
        StepKind kind,
        ClassName executionClass,
        Optional<String> delegatedMethodName,
        @Nullable ClassName inboundMapper,
        @Nullable ClassName outboundMapper,
        @Nullable ClassName externalMapper,
        MapperFallbackMode mapperFallback,
        @Nullable ClassName inputType,
        @Nullable ClassName outputType,
        @Nullable StreamingShape streamingShapeHint,
        boolean runOnVirtualThreads,
        List<String> accepts,
        boolean terminal
    ) {
        this(name, requireAuthoredKind(kind), executionClass, delegatedMethodName, null,
            null, null, null, Map.of(), null, Map.of(), List.of(), inboundMapper, outboundMapper,
            externalMapper, mapperFallback, inputType, outputType, streamingShapeHint, runOnVirtualThreads,
            accepts, terminal, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Internal constructor shape for legacy Query parser and focused compiler fixtures. */
    public StepDefinition(
        String name,
        StepKind kind,
        @Nullable ClassName executionClass,
        @Nullable PipelineTemplateStepExecution remoteExecution,
        Map<?, ?> removedAwaitConfig,
        @Nullable String removedTimeout,
        List<?> removedIdempotencyKeyFields,
        @Nullable String queryId,
        Map<?, ?> queryConfig,
        List<?> queryKeyFields,
        @Nullable ClassName inboundMapper,
        @Nullable ClassName outboundMapper,
        @Nullable ClassName externalMapper,
        MapperFallbackMode mapperFallback,
        @Nullable ClassName inputType,
        @Nullable ClassName outputType,
        @Nullable StreamingShape streamingShapeHint,
        boolean runOnVirtualThreads
    ) {
        this(name, kind, executionClass, Optional.empty(), remoteExecution,
            null, null, null, Map.of(), queryId, copyObjectMap(queryConfig), copyStringList(queryKeyFields),
            inboundMapper, outboundMapper, externalMapper, mapperFallback, inputType, outputType,
            streamingShapeHint, runOnVirtualThreads, List.of(), false,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        rejectRemovedAwait(removedAwaitConfig, removedTimeout, removedIdempotencyKeyFields);
    }

    /** Internal constructor shape retained while parser call sites migrate off standalone Await fields. */
    public StepDefinition(
        String name,
        StepKind kind,
        @Nullable ClassName executionClass,
        @Nullable PipelineTemplateStepExecution remoteExecution,
        Map<String, Object> removedAwaitConfig,
        @Nullable String removedTimeout,
        List<String> removedIdempotencyKeyFields,
        @Nullable String command,
        @Nullable ClassName commandIdGenerator,
        @Nullable String duplicatePolicy,
        Map<String, Object> commandConfig,
        @Nullable String queryId,
        Map<String, Object> queryConfig,
        List<String> queryKeyFields,
        @Nullable ClassName inboundMapper,
        @Nullable ClassName outboundMapper,
        @Nullable ClassName externalMapper,
        MapperFallbackMode mapperFallback,
        @Nullable ClassName inputType,
        @Nullable ClassName outputType,
        @Nullable StreamingShape streamingShapeHint,
        boolean runOnVirtualThreads,
        List<String> accepts,
        boolean terminal
    ) {
        this(name, kind, executionClass, Optional.empty(), remoteExecution,
            command, commandIdGenerator, duplicatePolicy, commandConfig, queryId, queryConfig, queryKeyFields,
            inboundMapper, outboundMapper, externalMapper, mapperFallback, inputType, outputType,
            streamingShapeHint, runOnVirtualThreads, accepts, terminal,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        rejectRemovedAwait(removedAwaitConfig, removedTimeout, removedIdempotencyKeyFields);
    }

    /** Internal constructor shape retained while dynamic-operation parsing migrates. */
    public StepDefinition(
        String name,
        StepKind kind,
        @Nullable ClassName executionClass,
        Optional<String> delegatedMethodName,
        @Nullable PipelineTemplateStepExecution remoteExecution,
        Map<String, Object> removedAwaitConfig,
        @Nullable String removedTimeout,
        List<String> removedIdempotencyKeyFields,
        @Nullable String command,
        @Nullable ClassName commandIdGenerator,
        @Nullable String duplicatePolicy,
        Map<String, Object> commandConfig,
        @Nullable String queryId,
        Map<String, Object> queryConfig,
        List<String> queryKeyFields,
        @Nullable ClassName inboundMapper,
        @Nullable ClassName outboundMapper,
        @Nullable ClassName externalMapper,
        MapperFallbackMode mapperFallback,
        @Nullable ClassName inputType,
        @Nullable ClassName outputType,
        @Nullable StreamingShape streamingShapeHint,
        boolean runOnVirtualThreads,
        List<String> accepts,
        boolean terminal,
        Optional<String> pipelineReference,
        Optional<String> dynamicOperationSource
    ) {
        this(name, kind, executionClass, delegatedMethodName, remoteExecution,
            command, commandIdGenerator, duplicatePolicy, commandConfig, queryId, queryConfig, queryKeyFields,
            inboundMapper, outboundMapper, externalMapper, mapperFallback, inputType, outputType,
            streamingShapeHint, runOnVirtualThreads, accepts, terminal,
            pipelineReference, dynamicOperationSource, Optional.empty(), Optional.empty());
        rejectRemovedAwait(removedAwaitConfig, removedTimeout, removedIdempotencyKeyFields);
    }

    private static StepKind requireAuthoredKind(StepKind kind) {
        if (kind == StepKind.REMOTE || kind == StepKind.COMMAND || kind == StepKind.QUERY || kind == StepKind.PIPELINE) {
            throw new IllegalArgumentException("Convenience constructor cannot be used for " + kind);
        }
        return kind;
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return Map.copyOf(copy);
    }

    private static List<String> copyStringList(List<?> values) {
        return values == null ? List.of() : values.stream().map(String::valueOf).toList();
    }

    private static void rejectRemovedAwait(Map<?, ?> config, String timeout, List<?> idempotency) {
        if ((config != null && !config.isEmpty()) || timeout != null || (idempotency != null && !idempotency.isEmpty())) {
            throw new IllegalArgumentException("standalone Await fields were removed; use deferredCompletion");
        }
    }

    private static Optional<String> normalized(Optional<String> value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        String normalized = value.orElseThrow().trim();
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }
}
