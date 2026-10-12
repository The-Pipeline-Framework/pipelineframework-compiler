package org.pipelineframework.processor.renderer;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import javax.lang.model.element.Modifier;
import org.pipelineframework.config.boundary.PipelineHttpPayloadBoundaryConfig;
import org.pipelineframework.config.template.PipelinePlatform;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.processor.phase.NamingPolicy;

/** Emits Quarkus REST adapters for compiler-pinned owned payload boundaries. */
public final class HttpPayloadBoundaryRenderer {
    private static final ClassName PATH = ClassName.get("jakarta.ws.rs", "Path");
    private static final ClassName POST = ClassName.get("jakarta.ws.rs", "POST");
    private static final ClassName HEADER = ClassName.get("jakarta.ws.rs", "HeaderParam");
    private static final ClassName CONTEXT = ClassName.get("jakarta.ws.rs.core", "Context");
    private static final ClassName SECURITY = ClassName.get("jakarta.ws.rs.core", "SecurityContext");
    private static final ClassName RESPONSE = ClassName.get("jakarta.ws.rs.core", "Response");
    private static final ClassName HTTP_RESPONSE = ClassName.get("io.vertx.core.http", "HttpServerResponse");
    private static final ClassName BOUNDARY = ClassName.get(PipelineHttpPayloadBoundaryConfig.class);
    private static final ClassName TRANSFER = ClassName.get("org.pipelineframework.connector", "OwnedPayloadTransfer");

    public java.util.List<String> render(PipelineTemplateConfig config, Path outputDir) throws IOException {
        java.util.List<String> classes = new java.util.ArrayList<>();
        int index = 0;
        for (PipelineHttpPayloadBoundaryConfig boundary : config.httpPayloads().values().stream()
            .sorted(Comparator.comparing(PipelineHttpPayloadBoundaryConfig::name)).toList()) {
            String className = "GeneratedPayloadBoundary" + index++;
            TypeSpec.Builder resource = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(PATH).addMember("value", "$S",
                    "/tpf/payloads/" + boundary.name() + "/"
                        + boundary.direction().name().toLowerCase(java.util.Locale.ROOT)).build())
                .addAnnotation(ClassName.get("io.quarkus.security", "Authenticated"))
                .addAnnotation(ClassName.get("io.smallrye.common.annotation", "Blocking"))
                .addField(FieldSpec.builder(ClassName.get("org.pipelineframework.connector", "ConnectorBindingRegistry"),
                    "bindings", Modifier.PRIVATE).addAnnotation(ClassName.get("jakarta.inject", "Inject")).build())
                .addField(FieldSpec.builder(ClassName.get("org.pipelineframework.connector", "ConnectorRuntimeContext"),
                    "runtimeContext", Modifier.PRIVATE).addAnnotation(ClassName.get("jakarta.inject", "Inject")).build())
                .addField(FieldSpec.builder(com.squareup.javapoet.ParameterizedTypeName.get(
                    ClassName.get("jakarta.enterprise.inject", "Instance"),
                    ClassName.get("org.pipelineframework.connector", "PayloadBoundaryAuthorizer")),
                    "authorizers", Modifier.PRIVATE).addAnnotation(ClassName.get("jakarta.inject", "Inject")).build())
                .addField(FieldSpec.builder(BOUNDARY, "BOUNDARY", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T($S, $T.Direction.$L, $S, $S, $S, $L, $LL, $S)",
                        BOUNDARY, boundary.name(), BOUNDARY, boundary.direction().name(), boundary.objectName(),
                        boundary.canonicalType(), boundary.referenceField(), contentTypes(boundary),
                        boundary.maxBytes(), boundary.authorizationScope()).build())
                .addField(FieldSpec.builder(ClassName.get("org.pipelineframework.config.pipeline", "PipelineYamlConfig"),
                    "CONFIG", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.load()", ClassName.get("org.pipelineframework.connector", "PayloadBoundaryRuntimeConfig"))
                    .build());
            resource.addMethod(MethodSpec.methodBuilder("transfer")
                .addModifiers(Modifier.PRIVATE)
                .returns(TRANSFER)
                .addStatement("if (authorizers.isUnsatisfied() || authorizers.isAmbiguous()) throw new $T(\"exactly one payload boundary authorizer is required\")",
                    IllegalStateException.class)
                .addStatement("return new $T(bindings, runtimeContext, authorizers.get())", TRANSFER)
                .build());
            resource.addMethod(MethodSpec.methodBuilder("principal")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(SECURITY, "security")
                .addStatement("if (security == null || security.getUserPrincipal() == null) throw new $T(401)",
                    ClassName.get("jakarta.ws.rs", "WebApplicationException"))
                .addStatement("return security.getUserPrincipal().getName()")
                .build());
            if (config.platform() == PipelinePlatform.FUNCTION) {
                if (boundary.direction() == PipelineHttpPayloadBoundaryConfig.Direction.UPLOAD) {
                    resource.addMethod(initiateDelegated(boundary));
                    resource.addMethod(delegatedPart(boundary));
                    resource.addMethod(completeDelegated(boundary));
                    resource.addMethod(abortDelegated(boundary));
                } else {
                    resource.addMethod(delegatedDownload(boundary));
                }
            } else {
                resource.addMethod(boundary.direction() == PipelineHttpPayloadBoundaryConfig.Direction.UPLOAD
                    ? upload(boundary) : download(boundary));
            }
            JavaFile.builder(config.basePackage() + NamingPolicy.PIPELINE_PACKAGE_SUFFIX, resource.build())
                .build().writeTo(outputDir);
            classes.add(config.basePackage() + NamingPolicy.PIPELINE_PACKAGE_SUFFIX + "." + className);
        }
        return java.util.List.copyOf(classes);
    }

    private MethodSpec upload(PipelineHttpPayloadBoundaryConfig boundary) {
        return MethodSpec.methodBuilder("upload")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(POST)
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Consumes"))
                .addMember("value", "$S", "*/*").build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Produces"))
                .addMember("value", "$S", "application/json").build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.eclipse.microprofile.openapi.annotations", "Operation"))
                .addMember("operationId", "$S", "uploadPayload" + boundary.name()).build())
            .returns(com.squareup.javapoet.ParameterizedTypeName.get(
                ClassName.get(java.util.Map.class), ClassName.get(String.class),
                ClassName.get("org.pipelineframework.repository", "PayloadReference")))
            .addParameter(com.squareup.javapoet.ParameterSpec.builder(java.io.InputStream.class, "body")
                .addAnnotation(AnnotationSpec.builder(ClassName.get(
                    "org.eclipse.microprofile.openapi.annotations.parameters", "RequestBody"))
                    .addMember("description", "$S",
                        "Multipart body with exactly one file part named file; send the raw multipart request body")
                    .addMember("content", "@$T(mediaType = $S)", ClassName.get(
                        "org.eclipse.microprofile.openapi.annotations.media", "Content"),
                        "multipart/form-data").build()).build())
            .addParameter(header("contentType", "Content-Type"))
            .addParameter(header("tenant", "X-Tenant-Id"))
            .addParameter(header("scope", "X-Scope-Id"))
            .addParameter(com.squareup.javapoet.ParameterSpec.builder(SECURITY, "security")
                .addAnnotation(CONTEXT).build())
            .addParameter(com.squareup.javapoet.ParameterSpec.builder(HTTP_RESPONSE, "httpResponse")
                .addAnnotation(CONTEXT).build())
            .addException(IOException.class)
            .addStatement("var target = CONFIG.publish().get(BOUNDARY.objectName())")
            .addStatement("if (target == null) throw new $T(\"payload target is unavailable\")",
                IllegalStateException.class)
            .addStatement("var cancelled = new $T()", java.util.concurrent.atomic.AtomicBoolean.class)
            .addStatement("httpResponse.closeHandler(ignored -> cancelled.set(true))")
            .beginControlFlow("try (var input = new $T(body, contentType))",
                ClassName.get("org.pipelineframework.connector", "OwnedPayloadMultipart"))
            .addStatement("var reference = transfer().upload(BOUNDARY, target, principal(security), tenant, scope, input.mediaType(), input, cancelled::get)")
            .addStatement("return $T.of(BOUNDARY.referenceField(), reference)", java.util.Map.class)
            .nextControlFlow("catch ($T oversized)", ClassName.get("org.pipelineframework.connector", "PayloadTooLargeException"))
            .addStatement("throw new $T(oversized, 413)", ClassName.get("jakarta.ws.rs", "WebApplicationException"))
            .nextControlFlow("catch ($T media)", ClassName.get("org.pipelineframework.connector", "PayloadMediaTypeException"))
            .addStatement("throw new $T(media, 415)", ClassName.get("jakarta.ws.rs", "WebApplicationException"))
            .nextControlFlow("catch ($T denied)", SecurityException.class)
            .addStatement("throw new $T()", ClassName.get("jakarta.ws.rs", "ForbiddenException"))
            .nextControlFlow("catch ($T invalid)", IllegalArgumentException.class)
            .addStatement("throw new $T()", ClassName.get("jakarta.ws.rs", "BadRequestException"))
            .endControlFlow()
            .build();
    }

    private MethodSpec download(PipelineHttpPayloadBoundaryConfig boundary) {
        return MethodSpec.methodBuilder("download")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(POST)
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Consumes"))
                .addMember("value", "$S", "application/json").build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.eclipse.microprofile.openapi.annotations", "Operation"))
                .addMember("operationId", "$S", "downloadPayload" + boundary.name()).build())
            .returns(RESPONSE)
            .addParameter(ClassName.get("org.pipelineframework.repository", "PayloadReference"), "reference")
            .addParameter(header("tenant", "X-Tenant-Id"))
            .addParameter(header("scope", "X-Scope-Id"))
            .addParameter(header("range", "Range"))
            .addParameter(com.squareup.javapoet.ParameterSpec.builder(SECURITY, "security")
                .addAnnotation(CONTEXT).build())
            .addParameter(com.squareup.javapoet.ParameterSpec.builder(HTTP_RESPONSE, "httpResponse")
                .addAnnotation(CONTEXT).build())
            .addStatement("if (range != null) throw new $T(416)",
                ClassName.get("jakarta.ws.rs", "WebApplicationException"))
            .addStatement("var source = CONFIG.sources().get(BOUNDARY.objectName())")
            .addStatement("if (source == null) throw new $T(\"payload source is unavailable\")",
                IllegalStateException.class)
            .addStatement("var engine = transfer()")
            .addStatement("var principal = principal(security)")
            .addStatement("$T.DownloadLease lease", TRANSFER)
            .beginControlFlow("try")
            .addStatement("lease = engine.openDownload(BOUNDARY, source, principal, tenant, scope, reference)")
            .nextControlFlow("catch ($T denied)", SecurityException.class)
            .addStatement("throw new $T()", ClassName.get("jakarta.ws.rs", "ForbiddenException"))
            .nextControlFlow("catch ($T invalid)", IllegalArgumentException.class)
            .addStatement("throw new $T()", ClassName.get("jakarta.ws.rs", "BadRequestException"))
            .endControlFlow()
            .addStatement("httpResponse.closeHandler(ignored -> lease.close())")
            .addStatement("$T stream = output -> { try (lease) { lease.writeTo(output); } }",
                ClassName.get("jakarta.ws.rs.core", "StreamingOutput"))
            .addStatement("var response = $T.ok(stream, reference.contentType()).header(\"Content-Length\", reference.sizeBytes())"
                + ".header(\"Cache-Control\", \"private, no-store\")"
                + ".header(\"Content-Disposition\", \"attachment; filename=\\\"payload\\\"\")"
                + ".header(\"Accept-Ranges\", \"none\")", RESPONSE)
            .addStatement("if (reference.checksum() != null) response.header(\"ETag\", \"\\\"\" + reference.checksum() + \"\\\"\")")
            .addStatement("return response.build()")
            .build();
    }

    private MethodSpec initiateDelegated(PipelineHttpPayloadBoundaryConfig boundary) {
        ClassName request = ClassName.get("org.pipelineframework.connector", "DelegatedUploadInitiation");
        ClassName reservation = ClassName.get("org.pipelineframework.connector", "DelegatedPayloadTarget", "Reservation");
        return MethodSpec.methodBuilder("initiate")
            .addModifiers(Modifier.PUBLIC).addAnnotation(POST)
            .addAnnotation(AnnotationSpec.builder(PATH).addMember("value", "$S", "initiate").build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Consumes"))
                .addMember("value", "$S", "application/json").build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.eclipse.microprofile.openapi.annotations", "Operation"))
                .addMember("operationId", "$S", "initiatePayload" + boundary.name()).build())
            .returns(reservation).addParameter(request, "request")
            .addParameter(header("tenant", "X-Tenant-Id"))
            .addParameter(header("scope", "X-Scope-Id"))
            .addParameter(com.squareup.javapoet.ParameterSpec.builder(SECURITY, "security").addAnnotation(CONTEXT).build())
            .addStatement("if (request == null) throw new $T()", ClassName.get("jakarta.ws.rs", "BadRequestException"))
            .addStatement("var target = CONFIG.publish().get(BOUNDARY.objectName())")
            .addStatement("if (target == null) throw new $T(\"payload target is unavailable\")", IllegalStateException.class)
            .beginControlFlow("try")
            .addStatement("return transfer().initiateDelegatedUpload(BOUNDARY, target, principal(security), tenant, scope, request.contentType(), request.bytes(), request.sha256(), request.idempotencyKey())")
            .nextControlFlow("catch ($T media)", ClassName.get("org.pipelineframework.connector", "PayloadMediaTypeException"))
            .addStatement("throw new $T(415)", ClassName.get("jakarta.ws.rs", "WebApplicationException"))
            .nextControlFlow("catch ($T denied)", SecurityException.class)
            .addStatement("throw new $T()", ClassName.get("jakarta.ws.rs", "ForbiddenException"))
            .nextControlFlow("catch ($T oversized)",
                ClassName.get("org.pipelineframework.connector", "PayloadTooLargeException"))
            .addStatement("throw new $T(oversized, 413)",
                ClassName.get("jakarta.ws.rs", "WebApplicationException"))
            .nextControlFlow("catch ($T invalid)", IllegalArgumentException.class)
            .addStatement("throw new $T()", ClassName.get("jakarta.ws.rs", "BadRequestException"))
            .endControlFlow().build();
    }

    private MethodSpec delegatedPart(PipelineHttpPayloadBoundaryConfig boundary) {
        return delegatedControl(boundary, "part", "part", "DelegatedUploadPartRequest",
            ClassName.get("org.pipelineframework.connector", "DelegatedPayloadTarget", "PartCapability"),
            "return transfer().delegatedUploadPart(BOUNDARY, target, principal(security), tenant, scope, request.token(), request.partNumber())");
    }

    private MethodSpec completeDelegated(PipelineHttpPayloadBoundaryConfig boundary) {
        return delegatedControl(boundary, "complete", "complete", "DelegatedUploadTokenRequest",
            com.squareup.javapoet.ParameterizedTypeName.get(ClassName.get(java.util.Map.class),
                ClassName.get(String.class), ClassName.get("org.pipelineframework.repository", "PayloadReference")),
            "return java.util.Map.of(BOUNDARY.referenceField(), transfer().completeDelegatedUpload(BOUNDARY, target, principal(security), tenant, scope, request.token()))");
    }

    private MethodSpec abortDelegated(PipelineHttpPayloadBoundaryConfig boundary) {
        return delegatedControl(boundary, "abort", "abort", "DelegatedUploadTokenRequest",
            com.squareup.javapoet.TypeName.VOID,
            "transfer().abortDelegatedUpload(BOUNDARY, target, principal(security), tenant, scope, request.token())");
    }

    private MethodSpec delegatedControl(PipelineHttpPayloadBoundaryConfig boundary,
                                        String name, String path, String requestType,
                                        com.squareup.javapoet.TypeName responseType, String statement) {
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC).addAnnotation(POST)
            .addAnnotation(AnnotationSpec.builder(PATH).addMember("value", "$S", path).build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Consumes"))
                .addMember("value", "$S", "application/json").build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.eclipse.microprofile.openapi.annotations", "Operation"))
                .addMember("operationId", "$S", name + "Payload" + boundary.name()).build())
            .returns(responseType)
            .addParameter(ClassName.get("org.pipelineframework.connector", requestType), "request")
            .addParameter(header("tenant", "X-Tenant-Id"))
            .addParameter(header("scope", "X-Scope-Id"))
            .addParameter(com.squareup.javapoet.ParameterSpec.builder(SECURITY, "security").addAnnotation(CONTEXT).build())
            .addStatement("if (request == null) throw new $T()", ClassName.get("jakarta.ws.rs", "BadRequestException"))
            .addStatement("var target = CONFIG.publish().get(BOUNDARY.objectName())")
            .addStatement("if (target == null) throw new $T(\"payload target is unavailable\")", IllegalStateException.class)
            .beginControlFlow("try")
            .addStatement(statement)
            .nextControlFlow("catch ($T denied)", SecurityException.class)
            .addStatement("throw new $T()", ClassName.get("jakarta.ws.rs", "ForbiddenException"))
            .nextControlFlow("catch ($T invalid)", IllegalArgumentException.class)
            .addStatement("throw new $T()", ClassName.get("jakarta.ws.rs", "BadRequestException"))
            .endControlFlow().build();
    }

    private MethodSpec delegatedDownload(PipelineHttpPayloadBoundaryConfig boundary) {
        return MethodSpec.methodBuilder("download").addModifiers(Modifier.PUBLIC).addAnnotation(POST)
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Consumes"))
                .addMember("value", "$S", "application/json").build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.eclipse.microprofile.openapi.annotations", "Operation"))
                .addMember("operationId", "$S", "downloadPayloadCapability" + boundary.name()).build())
            .returns(ClassName.get("org.pipelineframework.connector", "DelegatedPayloadSource", "DownloadCapability"))
            .addParameter(ClassName.get("org.pipelineframework.repository", "PayloadReference"), "reference")
            .addParameter(header("tenant", "X-Tenant-Id"))
            .addParameter(header("scope", "X-Scope-Id"))
            .addParameter(com.squareup.javapoet.ParameterSpec.builder(SECURITY, "security").addAnnotation(CONTEXT).build())
            .addStatement("var source = CONFIG.sources().get(BOUNDARY.objectName())")
            .addStatement("if (source == null) throw new $T(\"payload source is unavailable\")", IllegalStateException.class)
            .beginControlFlow("try")
            .addStatement("return transfer().delegatedDownload(BOUNDARY, source, principal(security), tenant, scope, reference)")
            .nextControlFlow("catch ($T denied)", SecurityException.class)
            .addStatement("throw new $T()", ClassName.get("jakarta.ws.rs", "ForbiddenException"))
            .nextControlFlow("catch ($T invalid)", IllegalArgumentException.class)
            .addStatement("throw new $T()", ClassName.get("jakarta.ws.rs", "BadRequestException"))
            .endControlFlow().build();
    }

    private static com.squareup.javapoet.ParameterSpec header(String name, String key) {
        return com.squareup.javapoet.ParameterSpec.builder(String.class, name)
            .addAnnotation(AnnotationSpec.builder(HEADER).addMember("value", "$S", key).build()).build();
    }

    private static String contentTypes(PipelineHttpPayloadBoundaryConfig boundary) {
        return "java.util.List.of(" + boundary.contentTypes().stream()
            .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(", ")) + ")";
    }
}
