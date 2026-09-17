/*
 * Copyright (c) 2026 Mariano Barcia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.pipelineframework.proto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Renders the shared protocol schema into pipeline-local protobuf contracts. */
final class PayloadReferenceProtoSchema {
    private static final String SCHEMA_RESOURCE = "/payload_reference_storage.proto";
    private static final List<String> SHARED_MESSAGES = List.of("ConnectorPayloadOrigin", "PayloadReference");

    private PayloadReferenceProtoSchema() {
    }

    static void renderMessages(StringBuilder builder) {
        String schema = loadSchema();
        for (int index = 0; index < SHARED_MESSAGES.size(); index++) {
            if (index > 0) {
                builder.append('\n');
            }
            builder.append(messageDeclaration(schema, SHARED_MESSAGES.get(index)));
        }
    }

    private static String loadSchema() {
        try (InputStream stream = PayloadReferenceProtoSchema.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing shared protocol schema " + SCHEMA_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read shared protocol schema " + SCHEMA_RESOURCE, failure);
        }
    }

    private static String messageDeclaration(String schema, String messageName) {
        String declaration = "message " + messageName;
        int start = schema.indexOf(declaration);
        if (start < 0) {
            throw new IllegalStateException("Shared protocol schema does not declare " + messageName);
        }
        int openingBrace = schema.indexOf('{', start + declaration.length());
        if (openingBrace < 0) {
            throw new IllegalStateException("Shared protocol message has no body: " + messageName);
        }
        int depth = 0;
        for (int index = openingBrace; index < schema.length(); index++) {
            depth += switch (schema.charAt(index)) {
                case '{' -> 1;
                case '}' -> -1;
                default -> 0;
            };
            if (depth == 0) {
                return schema.substring(start, index + 1) + '\n';
            }
        }
        throw new IllegalStateException("Shared protocol message has an unterminated body: " + messageName);
    }
}
