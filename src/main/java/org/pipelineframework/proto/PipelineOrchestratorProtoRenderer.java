/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.pipelineframework.proto;

import java.util.*;
import org.pipelineframework.config.CardinalitySemantics;

final class PipelineOrchestratorProtoRenderer {

    String renderOrchestratorProto(String basePackage, List<ResolvedStep> steps, boolean v2, String typesProtoName) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        ResolvedStep first = steps.get(0);
        ResolvedStep last = steps.get(steps.size() - 1);
        StreamingShape shape = computePipelineStreamingShape(steps);

        StringBuilder builder = new StringBuilder();
        builder.append("syntax = \"proto3\";\n\n");
        builder.append("package ").append(basePackage).append(";\n\n");
        builder.append("option java_package = \"")
            .append(basePackage)
            .append(".grpc\";\n\n");
        if (v2) {
            builder.append("import \"").append(typesProtoName).append("\";\n");
        } else {
            builder.append("import \"")
                .append(first.serviceName())
                .append(".proto\";\n");
            if (!first.serviceName().equals(last.serviceName())) {
                builder.append("import \"")
                    .append(last.serviceName())
                    .append(".proto\";\n");
            }
        }
        builder.append("import \"google/protobuf/empty.proto\";\n");
        builder.append('\n');
        builder.append("message RunAsyncRequest {\n");
        builder.append("  ").append(first.inputTypeName()).append(" input = 1;\n");
        builder.append("  repeated ").append(first.inputTypeName()).append(" input_batch = 2;\n");
        builder.append("  string tenant_id = 3;\n");
        builder.append("  string idempotency_key = 4;\n");
        builder.append("}\n\n");
        builder.append("message RunAsyncResponse {\n");
        builder.append("  string execution_id = 1;\n");
        builder.append("  bool duplicate = 2;\n");
        builder.append("  string status_url = 3;\n");
        builder.append("  int64 accepted_at_epoch_ms = 4;\n");
        builder.append("}\n\n");
        builder.append("message GetExecutionStatusRequest {\n");
        builder.append("  string tenant_id = 1;\n");
        builder.append("  string execution_id = 2;\n");
        builder.append("}\n\n");
        builder.append("message GetExecutionStatusResponse {\n");
        builder.append("  string execution_id = 1;\n");
        builder.append("  string status = 2;\n");
        builder.append("  int32 current_step_index = 3;\n");
        builder.append("  int32 attempt = 4;\n");
        builder.append("  int64 version = 5;\n");
        builder.append("  int64 next_due_epoch_ms = 6;\n");
        builder.append("  int64 updated_at_epoch_ms = 7;\n");
        builder.append("  string error_code = 8;\n");
        builder.append("  string error_message = 9;\n");
        builder.append("}\n\n");
        builder.append("message GetExecutionResultRequest {\n");
        builder.append("  string tenant_id = 1;\n");
        builder.append("  string execution_id = 2;\n");
        builder.append("}\n\n");
        builder.append("message GetExecutionResultResponse {\n");
        builder.append("  repeated ").append(last.outputTypeName()).append(" items = 1;\n");
        builder.append("}\n\n");
        builder.append("message CompleteAwaitRequest {\n");
        builder.append("  string tenant_id = 1;\n");
        builder.append("  string interaction_id = 2;\n");
        builder.append("  string correlation_id = 3;\n");
        builder.append("  string idempotency_key = 4;\n");
        builder.append("  string response_json = 5;\n");
        builder.append("  string actor = 6;\n");
        builder.append("  string resume_token = 7;\n");
        builder.append("}\n\n");
        builder.append("message CompleteAwaitResponse {\n");
        builder.append("  string interaction_id = 1;\n");
        builder.append("  string execution_id = 2;\n");
        builder.append("  string step_id = 3;\n");
        builder.append("  string status = 4;\n");
        builder.append("  bool duplicate = 5;\n");
        builder.append("}\n\n");
        builder.append("message ListPendingAwaitRequest {\n");
        builder.append("  string tenant_id = 1;\n");
        builder.append("  string assignee = 2;\n");
        builder.append("  string group = 3;\n");
        builder.append("  string step_id = 4;\n");
        builder.append("  int32 limit = 5;\n");
        builder.append("}\n\n");
        builder.append("message AwaitInteraction {\n");
        builder.append("  string interaction_id = 1;\n");
        builder.append("  string correlation_id = 2;\n");
        builder.append("  string execution_id = 3;\n");
        builder.append("  string step_id = 4;\n");
        builder.append("  int32 step_index = 5;\n");
        builder.append("  string output_type = 6;\n");
        builder.append("  string status = 7;\n");
        builder.append("  string transport_type = 8;\n");
        builder.append("  int64 deadline_epoch_ms = 9;\n");
        builder.append("  int64 created_at_epoch_ms = 10;\n");
        builder.append("  int64 updated_at_epoch_ms = 11;\n");
        builder.append("  string request_payload_json = 12;\n");
        builder.append("}\n\n");
        builder.append("message ListPendingAwaitResponse {\n");
        builder.append("  repeated AwaitInteraction interactions = 1;\n");
        builder.append("}\n\n");
        builder.append("service OrchestratorService {\n");
        builder.append("  rpc Run (");
        if (shape.inputStreaming()) {
            builder.append("stream ");
        }
        builder.append(first.inputTypeName());
        builder.append(") returns (");
        if (shape.outputStreaming()) {
            builder.append("stream ");
        }
        builder.append(last.outputTypeName());
        builder.append(");\n");
        builder.append("  rpc Ingest (stream ")
            .append(first.inputTypeName())
            .append(") returns (stream ")
            .append(last.outputTypeName())
            .append(");\n");
        builder.append("  rpc RunAsync (RunAsyncRequest) returns (RunAsyncResponse);\n");
        builder.append("  rpc GetExecutionStatus (GetExecutionStatusRequest) returns (GetExecutionStatusResponse);\n");
        builder.append("  rpc GetExecutionResult (GetExecutionResultRequest) returns (GetExecutionResultResponse);\n");
        builder.append("  rpc CompleteAwait (CompleteAwaitRequest) returns (CompleteAwaitResponse);\n");
        builder.append("  rpc ListPendingAwait (ListPendingAwaitRequest) returns (ListPendingAwaitResponse);\n");
        builder.append("  rpc Subscribe (google.protobuf.Empty) returns (stream ")
            .append(last.outputTypeName())
            .append(");\n");
        builder.append("}\n");
        return builder.toString();
    }

    private StreamingShape computePipelineStreamingShape(List<ResolvedStep> steps) {
        boolean inputStreaming = false;
        if (steps != null && !steps.isEmpty()) {
            inputStreaming = CardinalitySemantics.isStreamingInput(steps.get(0).cardinality());
        }
        boolean outputStreaming = inputStreaming;
        if (steps != null) {
            for (ResolvedStep step : steps) {
                outputStreaming = CardinalitySemantics.applyToOutputStreaming(step.cardinality(), outputStreaming);
            }
        }
        return new StreamingShape(inputStreaming, outputStreaming);
    }

}
