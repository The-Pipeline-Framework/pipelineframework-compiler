/*
 * Copyright (c) 2026 Mariano Barcia
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.pipelineframework.proto;

import java.util.List;
import org.pipelineframework.config.template.PipelineTemplateField;
import org.pipelineframework.config.template.PipelineTemplateStepExecution;

record ResolvedStep(
    String name,
    String serviceName,
    String serviceNameFormatted,
    String cardinality,
    String inputTypeName,
    List<PipelineTemplateField> inputFields,
    String outputTypeName,
    String operationOutputTypeName,
    List<PipelineTemplateField> outputFields,
    PipelineTemplateStepExecution execution,
    boolean pagedSource
) {
}

record StreamingShape(boolean inputStreaming, boolean outputStreaming) {
}

record AspectDefinition(String name, String position, List<String> enabledTargets) {
}
