/*
 * Copyright (c) 2026 Mariano Barcia
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

import java.util.Optional;
import java.util.Objects;
import com.squareup.javapoet.ClassName;
import org.pipelineframework.config.template.PipelineTemplateStepExecution;

/** Compiler-owned execution provenance, distinct from the semantic step kind. */
public enum StepExecutionShape {
    INTERNAL(StepKind.INTERNAL) {
        @Override void validate(Contract c) { Objects.requireNonNull(c.executionClass(), "executionClass"); }
    },
    DYNAMIC_OPERATION(StepKind.INTERNAL) {
        @Override void validate(Contract c) {
            if (c.kind() != StepKind.INTERNAL || c.executionClass() != null || c.remoteExecution() != null) {
                throw new IllegalArgumentException("dynamic operation bindings use INTERNAL semantics without authored execution");
            }
            requireTypes(c);
        }
    },
    DELEGATED(StepKind.DELEGATED) {
        @Override void validate(Contract c) { Objects.requireNonNull(c.executionClass(), "executionClass"); }
    },
    REMOTE(StepKind.REMOTE) {
        @Override void validate(Contract c) { Objects.requireNonNull(c.remoteExecution(), "remoteExecution"); }
    },
    COMMAND(StepKind.COMMAND) {
        @Override void validate(Contract c) {
            requireNoAuthoredExecution(c);
            requireTypes(c);
            if (c.command() == null || c.command().isBlank()) {
                throw new IllegalArgumentException("command cannot be blank for COMMAND steps");
            }
            Objects.requireNonNull(c.commandIdGenerator(), "commandIdGenerator");
        }
    },
    QUERY(StepKind.QUERY) {
        @Override void validate(Contract c) {
            requireNoAuthoredExecution(c);
            requireTypes(c);
            if (c.queryId() == null || c.queryId().isBlank()) {
                throw new IllegalArgumentException("queryId cannot be blank for QUERY steps");
            }
        }
    },
    PIPELINE(StepKind.PIPELINE) {
        @Override void validate(Contract c) {
            requireNoAuthoredExecution(c);
            requireTypes(c);
            if (c.pipelineReference().isEmpty()) {
                throw new IllegalArgumentException("pipelineReference cannot be blank for PIPELINE steps");
            }
        }
    };

    private final StepKind kind;

    StepExecutionShape(StepKind kind) { this.kind = kind; }

    public StepKind kind() { return kind; }

    abstract void validate(Contract contract);

    static StepExecutionShape from(StepKind kind, Optional<String> dynamicOperationSource) {
        if (dynamicOperationSource.isPresent()) return DYNAMIC_OPERATION;
        return switch (kind) {
            case INTERNAL -> INTERNAL;
            case DELEGATED -> DELEGATED;
            case REMOTE -> REMOTE;
            case COMMAND -> COMMAND;
            case QUERY -> QUERY;
            case PIPELINE -> PIPELINE;
        };
    }

    private static void requireTypes(Contract c) {
        Objects.requireNonNull(c.inputType(), "inputType");
        Objects.requireNonNull(c.outputType(), "outputType");
    }

    private static void requireNoAuthoredExecution(Contract c) {
        if (c.executionClass() != null || c.remoteExecution() != null) {
            throw new IllegalArgumentException(c.kind() + " steps cannot declare authored execution");
        }
    }

    record Contract(StepKind kind, ClassName executionClass, PipelineTemplateStepExecution remoteExecution,
                    ClassName inputType, ClassName outputType, Optional<String> pipelineReference,
                    String command, ClassName commandIdGenerator, String queryId) { }
}
