/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.pipelineframework.proto;

import java.util.*;
import org.pipelineframework.config.CardinalitySemantics;
import org.pipelineframework.config.template.*;

final class PipelineStepProtoRenderer {

    String renderStepProto(
        String basePackage,
        ResolvedStep step,
        ResolvedStep previous,
        boolean firstStep,
        List<AspectDefinition> aspects,
        boolean v2,
        String typesProtoName
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("syntax = \"proto3\";\n\n");
        builder.append("package ").append(basePackage).append(";\n\n");
        builder.append("option java_package = \"")
            .append(basePackage)
            .append(".grpc\";\n\n");

        if (v2) {
            builder.append("import \"").append(typesProtoName).append("\";\n\n");
        } else if (!firstStep && previous != null) {
            builder.append("import \"")
                .append(previous.serviceName())
                .append(".proto\";\n\n");
        }

        if (!v2 && firstStep) {
            renderLegacyMessage(builder, step.inputTypeName(), step.inputFields(), 1);
            builder.append('\n');
        }

        if (!v2) {
            if (!java.util.Objects.equals(step.operationOutputTypeName(), step.outputTypeName())) {
                throw new IllegalStateException("Deferred completion with distinct operation and final output types "
                    + "requires pipeline template version 2 or later; version 1 has no separate operation-output "
                    + "field schema for step '" + step.name() + "'");
            }
            int outputStartNumber = 1;
            List<PipelineTemplateField> inputFields = step.inputFields();
            if (inputFields != null && !inputFields.isEmpty()) {
                outputStartNumber = inputFields.size() + 1;
            }
            renderLegacyMessage(builder, step.operationOutputTypeName(), step.outputFields(), outputStartNumber);
            builder.append('\n');
        }

        if (step.pagedSource()) {
            renderPageMessages(builder, step, firstStep ? step.inputTypeName() : previous.outputTypeName());
        }
        renderService(builder, step, previous, firstStep, v2);
        builder.append('\n');
        renderAspectServices(builder, step, firstStep, aspects);
        return builder.toString();
    }

    private void renderLegacyMessage(
        StringBuilder builder,
        String typeName,
        List<PipelineTemplateField> fields,
        int startNumber
    ) {
        builder.append("message ").append(typeName).append(" {\n");
        int index = 0;
        if (fields != null) {
            for (PipelineTemplateField field : fields) {
                if (field == null || field.name() == null || field.name().isBlank()) {
                    continue;
                }
                String fieldType = renderLegacyFieldType(field);
                int number = startNumber + index;
                builder.append("  ").append(fieldType).append(' ')
                    .append(field.name()).append(" = ")
                    .append(number).append(";\n");
                index++;
            }
        }
        builder.append("}\n");
    }

    private String renderLegacyFieldType(PipelineTemplateField field) {
        String baseType = field.protoType();
        if (baseType == null || baseType.isBlank()) {
            System.getLogger(PipelineProtoGenerator.class.getName()).log(
                System.Logger.Level.WARNING,
                "Legacy field '{0}' is missing protoType; defaulting to string",
                field.name());
            baseType = "string";
        }
        if (field.repeated()) {
            return "repeated " + baseType;
        }
        return baseType;
    }

    private void renderService(
        StringBuilder builder,
        ResolvedStep step,
        ResolvedStep previous,
        boolean firstStep,
        boolean v2
    ) {
        builder.append("service Process")
            .append(step.serviceNameFormatted())
            .append("Service {\n");
        String inputType = (v2 || firstStep || previous == null) ? step.inputTypeName() : previous.outputTypeName();
        String outputType = step.operationOutputTypeName();
        CardinalitySemantics canonicalCardinality = CardinalitySemantics.fromString(step.cardinality());
        if (canonicalCardinality == CardinalitySemantics.ONE_TO_MANY) {
            builder.append("  rpc remoteProcess(")
                .append(inputType)
                .append(") returns (stream ")
                .append(outputType)
                .append(");\n");
            if (step.pagedSource()) {
                builder.append("  rpc remoteOpenPage(")
                    .append(step.serviceNameFormatted()).append("PageRequest)")
                    .append(" returns (stream ")
                    .append(step.serviceNameFormatted()).append("PageFrame);\n");
            }
        } else if (canonicalCardinality == CardinalitySemantics.MANY_TO_MANY) {
            builder.append("  rpc remoteProcess(stream ")
                .append(inputType)
                .append(") returns (stream ")
                .append(outputType)
                .append(");\n");
        } else if (canonicalCardinality == CardinalitySemantics.MANY_TO_ONE) {
            builder.append("  rpc remoteProcess(stream ")
                .append(inputType)
                .append(") returns (")
                .append(outputType)
                .append(");\n");
        } else {
            builder.append("  rpc remoteProcess(")
                .append(inputType)
                .append(") returns (")
                .append(outputType)
                .append(");\n");
        }
        builder.append("}\n");
    }

    private void renderPageMessages(StringBuilder builder, ResolvedStep step, String inputType) {
        String prefix = step.serviceNameFormatted();
        builder.append("message ").append(prefix).append("PageRequest {\n")
            .append("  ").append(inputType).append(" input = 1;\n")
            .append("  string source_identity = 2;\n")
            .append("  optional string start_checkpoint = 3;\n")
            .append("  int32 max_records = 4;\n")
            .append("  string pipeline_id = 5;\n")
            .append("  string contract_version = 6;\n")
            .append("  string release_version = 7;\n")
            .append("  string catalog_fingerprint = 8;\n")
            .append("}\n\n")
            .append("message ").append(prefix).append("PageCompletion {\n")
            .append("  int32 consumed_records = 1;\n")
            .append("  optional string next_checkpoint = 2;\n")
            .append("  bool exhausted = 3;\n")
            .append("}\n\n")
            .append("message ").append(prefix).append("PageFrame {\n")
            .append("  oneof payload {\n")
            .append("    ").append(step.operationOutputTypeName()).append(" item = 1;\n")
            .append("    ").append(prefix).append("PageCompletion completion = 2;\n")
            .append("  }\n}\n\n");
    }

    private void renderAspectServices(
        StringBuilder builder,
        ResolvedStep step,
        boolean firstStep,
        List<AspectDefinition> aspects
    ) {
        if (aspects == null || aspects.isEmpty()) {
            return;
        }
        List<AspectDefinition> beforeAspects = new ArrayList<>();
        List<AspectDefinition> afterAspects = new ArrayList<>();
        for (AspectDefinition aspect : aspects) {
            if ("BEFORE_STEP".equalsIgnoreCase(aspect.position())) {
                beforeAspects.add(aspect);
            } else {
                afterAspects.add(aspect);
            }
        }

        if (firstStep) {
            for (AspectDefinition aspect : beforeAspects) {
                renderAspectService(builder, aspect, step.inputTypeName());
            }
            if (!beforeAspects.isEmpty()) {
                builder.append('\n');
            }
        }

        for (AspectDefinition aspect : afterAspects) {
            renderAspectService(builder, aspect, step.operationOutputTypeName());
        }

        if (!afterAspects.isEmpty()) {
            builder.append('\n');
        }

        for (AspectDefinition aspect : beforeAspects) {
            renderAspectService(builder, aspect, step.operationOutputTypeName());
        }
    }

    private void renderAspectService(StringBuilder builder, AspectDefinition aspect, String typeName) {
        builder.append("service ")
            .append(observeServiceName(aspect.name(), typeName))
            .append(" {\n");
        builder.append("  rpc remoteProcess(")
            .append(typeName)
            .append(") returns (")
            .append(typeName)
            .append(");\n");
        builder.append("}\n");
    }

    private String observeServiceName(String aspectName, String typeName) {
        if (aspectName == null || aspectName.isBlank() || typeName == null || typeName.isBlank()) {
            return "";
        }
        String[] parts = aspectName.trim().split("[^A-Za-z0-9]+");
        StringBuilder aspectPascal = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            aspectPascal.append(Character.toUpperCase(lower.charAt(0)));
            if (lower.length() > 1) {
                aspectPascal.append(lower.substring(1));
            }
        }
        return "Observe" + aspectPascal + typeName.trim() + "SideEffectService";
    }

}
