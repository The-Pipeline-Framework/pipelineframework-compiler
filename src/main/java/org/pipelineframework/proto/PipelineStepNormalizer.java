/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.pipelineframework.proto;

import java.util.*;
import org.pipelineframework.config.template.*;

final class PipelineStepNormalizer {

    List<ResolvedStep> normalizeSteps(List<PipelineTemplateStep> steps, boolean v2, boolean v3) {
        List<ResolvedStep> resolved = new ArrayList<>();
        ResolvedStep previous = null;
        for (int i = 0; i < steps.size(); i++) {
            PipelineTemplateStep step = steps.get(i);
            String serviceName = toServiceName(step.name());
            String serviceNameFormatted = formatForClassName(stripProcessPrefix(step.name()));
            String inputTypeName = step.inputTypeName();
            List<PipelineTemplateField> inputFields = step.inputFields();
            if (!v2 && i > 0 && previous != null) {
                inputTypeName = previous.outputTypeName();
                inputFields = copyFields(previous.outputFields());
            }
            ResolvedStep resolvedStep = new ResolvedStep(
                step.name(),
                serviceName,
                serviceNameFormatted,
                step.cardinality(),
                inputTypeName,
                inputFields,
                step.outputTypeName(),
                step.operationOutputTypeName(),
                step.outputFields(),
                step.execution(),
                v3 && step.paging().isPresent());
            resolved.add(resolvedStep);
            previous = resolvedStep;
        }
        return resolved;
    }

    List<ResolvedStep> resolveV3AliasContracts(
        List<ResolvedStep> steps,
        PipelineTemplateTypeModel typeModel
    ) {
        return steps.stream().map(step -> new ResolvedStep(
            step.name(),
            step.serviceName(),
            step.serviceNameFormatted(),
            step.cardinality(),
            resolveV3ProtoContract(step.inputTypeName(), typeModel),
            step.inputFields(),
            resolveV3ProtoContract(step.outputTypeName(), typeModel),
            resolveV3ProtoContract(step.operationOutputTypeName(), typeModel),
            step.outputFields(),
            step.execution(),
            step.pagedSource())).toList();
    }

    private String resolveV3ProtoContract(
        String contract,
        PipelineTemplateTypeModel typeModel
    ) {
        if (contract == null || contract.isBlank()) {
            throw new IllegalStateException("Version: 3 steps require a non-blank logical contract.");
        }
        PipelineTemplateTypeReference reference = typeModel.resolveAliases(new PipelineTemplateTypeReference.Named(contract));
        if (reference instanceof PipelineTemplateTypeReference.Scalar) {
            throw new IllegalStateException("Version: 3 step contract '" + contract
                + "' resolves to a scalar; step contracts must resolve to a named message type.");
        }
        return PipelineTypesProtoRenderer.protoType(reference, typeModel);
    }

    private List<PipelineTemplateField> copyFields(List<PipelineTemplateField> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        return List.copyOf(fields);
    }

    List<AspectDefinition> toAspectDefinitions(Map<String, PipelineTemplateAspect> aspects) {
        if (aspects == null || aspects.isEmpty()) {
            return List.of();
        }
        List<AspectDefinition> definitions = new ArrayList<>();
        for (Map.Entry<String, PipelineTemplateAspect> entry : aspects.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            PipelineTemplateAspect aspect = entry.getValue();
            if (aspect == null || !aspect.enabled()) {
                continue;
            }
            String position = aspect.position();
            if (position == null || position.isBlank()) {
                position = "AFTER_STEP";
            }
            List<String> enabledTargets = readEnabledTargets(aspect.config());
            if (enabledTargets.isEmpty()) {
                continue;
            }
            if (!enabledTargets.contains("CLIENT_STEP") && !enabledTargets.contains("GRPC_SERVICE")) {
                continue;
            }
            definitions.add(new AspectDefinition(name, position, enabledTargets));
        }
        return definitions;
    }

    private List<String> readEnabledTargets(Map<String, Object> config) {
        if (config == null) {
            return List.of();
        }
        Object targetsObj = config.get("enabledTargets");
        if (!(targetsObj instanceof Iterable<?> targets)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object target : targets) {
            if (target != null) {
                String value = target.toString();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private String toServiceName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String replaced = name.replaceAll("[^A-Za-z0-9]", "-").toLowerCase(Locale.ROOT);
        String collapsed = replaced.replaceAll("-+", "-");
        if (collapsed.startsWith("-")) {
            collapsed = collapsed.substring(1);
        }
        if (collapsed.endsWith("-")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed + "-svc";
    }

    private String formatForClassName(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String[] parts = input.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            builder.append(Character.toUpperCase(lower.charAt(0)));
            if (lower.length() > 1) {
                builder.append(lower.substring(1));
            }
        }
        return builder.toString();
    }

    private String stripProcessPrefix(String name) {
        if (name == null) {
            return "";
        }
        if (name.startsWith("Process ")) {
            return name.substring("Process ".length());
        }
        return name;
    }

}
