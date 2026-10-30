package org.pipelineframework.proto;

/** Renders the opt-in, whole-pipeline Spring unary contract. */
final class SpringGrpcUnaryProtoRenderer {
    String render(String basePackage, ResolvedStep first, ResolvedStep last,
                  boolean sharedTypes, String typesProtoName) {
        StringBuilder proto = new StringBuilder()
            .append("syntax = \"proto3\";\n\n")
            .append("package ").append(basePackage).append(";\n\n")
            .append("option java_package = \"").append(basePackage).append(".grpc\";\n\n");
        if (sharedTypes) {
            proto.append("import \"").append(typesProtoName).append("\";\n\n");
        } else {
            proto.append("import \"").append(first.serviceName()).append(".proto\";\n");
            if (!first.serviceName().equals(last.serviceName())) {
                proto.append("import \"").append(last.serviceName()).append(".proto\";\n");
            }
            proto.append('\n');
        }
        return proto.append("service PipelineService {\n")
            .append("  rpc Run(").append(first.inputTypeName()).append(") returns (")
            .append(last.outputTypeName()).append(");\n")
            .append("}\n")
            .toString();
    }
}
