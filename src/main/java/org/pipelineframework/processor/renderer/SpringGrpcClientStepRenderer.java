package org.pipelineframework.processor.renderer;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.lang.model.element.Modifier;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.GrpcBinding;
import org.pipelineframework.processor.ir.StreamingShape;
import org.pipelineframework.processor.phase.NamingPolicy;
import org.pipelineframework.processor.util.GrpcJavaTypeResolver;
import org.pipelineframework.processor.util.ResourceNameUtils;

/** Spring-managed, nonblocking grpc-java clients for ordinary unary pipeline steps. */
public final class SpringGrpcClientStepRenderer implements PipelineRenderer<GrpcBinding> {
    @Override
    public GenerationTarget target() {
        return GenerationTarget.CLIENT_STEP;
    }

    @Override
    public void render(GrpcBinding binding, GenerationContext context) throws IOException {
        var model = binding.model();
        if (model.streamingShape() != StreamingShape.UNARY_UNARY || model.sideEffect()
            || model.deploymentRole() != DeploymentRole.ORCHESTRATOR_CLIENT
            || model.delegateService() != null || model.remoteExecution() != null
            || model.deferredCompletionSelection().isPresent()) {
            throw new IllegalStateException("Spring GRPC supports only ordinary unary client-role steps: "
                + model.serviceName());
        }
        var resolver = new GrpcJavaTypeResolver();
        var types = resolver.resolve(binding, context.compilerDiagnostics());
        var service = resolver.resolveJavaService(binding);
        var input = SpringGrpcMapperBinding.resolve(model, model.inputMapping(), model.inboundDomainType(),
            types.grpcParameterType(), context, true);
        var output = SpringGrpcMapperBinding.resolve(model, model.outputMapping(), model.outboundDomainType(),
            types.grpcReturnType(), context, false);
        String packageName = model.servicePackage() + NamingPolicy.PIPELINE_PACKAGE_SUFFIX;
        String className = ResourceNameUtils.normalizeBaseName(model.generatedName()) + "GrpcClientStep";
        String stubName = packageName + "." + className + ".stub";
        TypeSpec.Builder step = TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("org.springframework.stereotype", "Component"))
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.pipelineframework.annotation", "GeneratedRole"))
                .addMember("value", "$T.ORCHESTRATOR_CLIENT",
                    ClassName.get("org.pipelineframework.annotation", "GeneratedRole", "Role")).build())
            .addSuperinterface(ParameterizedTypeName.get(
                ClassName.get("org.pipelineframework.runtime.core", "PipelineUnaryStep"), input.domain(), output.domain()))
            .addField(service.futureStub(), "grpcClient", Modifier.PRIVATE, Modifier.FINAL);
        if (context.stepOrder() != null) {
            step.addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.core.annotation", "Order"))
                .addMember("value", "$L", context.stepOrder()).build());
        }
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
            .addParameter(ParameterSpec.builder(service.futureStub(), "grpcClient")
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.beans.factory.annotation", "Qualifier"))
                    .addMember("value", "$S", stubName).build()).build())
            .addStatement("this.grpcClient = grpcClient");
        addMapper(step, constructor, input, "inboundMapper");
        addMapper(step, constructor, output, "outboundMapper");
        step.addMethod(constructor.build()).addMethod(apply(input, output, service.methodName()));
        JavaFile.builder(packageName, step.build()).build().writeTo(context.outputDir());
        JavaFile.builder(packageName, configuration(className, stubName, model.serviceName(), service))
            .build().writeTo(context.outputDir());
    }

    private void addMapper(TypeSpec.Builder step, MethodSpec.Builder constructor,
                           SpringGrpcMapperBinding mapper, String name) {
        if (!mapper.generated()) {
            step.addField(FieldSpec.builder(ParameterizedTypeName.get(
                    ClassName.get("org.pipelineframework.mapper", "Mapper"), mapper.domain(), mapper.proto()),
                name, Modifier.PRIVATE, Modifier.FINAL).build());
            constructor.addParameter(mapper.mapper(), name).addStatement("this.$N = $N", name, name);
        }
    }

    private MethodSpec apply(SpringGrpcMapperBinding input, SpringGrpcMapperBinding output, String methodName) {
        ClassName metadata = ClassName.get("io.grpc", "Metadata");
        ClassName headers = ClassName.get("org.pipelineframework.runtime.core", "TpfContextHeaders");
        MethodSpec.Builder method = MethodSpec.methodBuilder("apply").addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC).addParameter(input.domain(), "input")
            .returns(ParameterizedTypeName.get(ClassName.get(CompletionStage.class), output.domain()))
            .addStatement("$T result = new $T<>()",
                ParameterizedTypeName.get(ClassName.get(CompletableFuture.class), output.domain()), CompletableFuture.class)
            .beginControlFlow("try")
            .addStatement("var context = $T.capture()",
                ClassName.get("org.pipelineframework.runtime.core", "TpfExecutionContext"))
            .addStatement("$T metadata = new $T()", metadata, metadata);
        for (String field : new String[]{"versionTag:VERSION", "replayMode:REPLAY", "cachePolicy:CACHE_POLICY"}) {
            String[] parts = field.split(":");
            method.addStatement("context.$L().ifPresent(value -> metadata.put($T.Key.of($T.$L, $T.ASCII_STRING_MARSHALLER), value))",
                parts[0], metadata, headers, parts[1], metadata);
        }
        method.addStatement("$T request = $T.requireNonNull($L, $S)", input.proto(), java.util.Objects.class,
                input.toExpression("input", "inboundMapper"), "Spring GRPC request mapper returned no message")
            .addStatement("var call = this.grpcClient.withInterceptors($T.newAttachHeadersInterceptor(metadata)).$L(request)",
                ClassName.get("io.grpc.stub", "MetadataUtils"), methodName)
            .beginControlFlow("result.whenComplete((value, failure) ->")
            .beginControlFlow("if (result.isCancelled())").addStatement("call.cancel(true)").endControlFlow()
            .endControlFlow(")")
            .addStatement("$T.addCallback(call, $L, $T.directExecutor())",
                ClassName.get("com.google.common.util.concurrent", "Futures"), callback(output),
                ClassName.get("com.google.common.util.concurrent", "MoreExecutors"))
            .nextControlFlow("catch ($T failure)", RuntimeException.class)
            .addStatement("result.completeExceptionally(failure)").endControlFlow()
            .addStatement("return result");
        return method.build();
    }

    private TypeSpec callback(SpringGrpcMapperBinding output) {
        return TypeSpec.anonymousClassBuilder("")
            .addSuperinterface(ParameterizedTypeName.get(
                ClassName.get("com.google.common.util.concurrent", "FutureCallback"), output.proto()))
            .addMethod(MethodSpec.methodBuilder("onSuccess").addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC).addParameter(output.proto(), "response")
                .beginControlFlow("try")
                .addStatement("$T.requireNonNull(response, $S)", java.util.Objects.class, "Spring GRPC received no message")
                .addStatement("result.complete($T.requireNonNull($L, $S))", java.util.Objects.class,
                    output.fromExpression("response", "outboundMapper"), "Spring GRPC response mapper returned no value")
                .nextControlFlow("catch ($T failure)", RuntimeException.class)
                .addStatement("result.completeExceptionally(failure)").endControlFlow().build())
            .addMethod(MethodSpec.methodBuilder("onFailure").addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC).addParameter(Throwable.class, "failure")
                .beginControlFlow("if (call.isCancelled())").addStatement("result.cancel(false)")
                .nextControlFlow("else").addStatement("result.completeExceptionally(failure)").endControlFlow().build())
            .build();
    }

    private TypeSpec configuration(String className, String stubName, String serviceName,
                                   GrpcJavaTypeResolver.GrpcJavaServiceTypes service) {
        String clientName = serviceName.replaceFirst("Service$", "")
            .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
            .replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        String property = "spring.grpc.client.channel." + clientName + ".target";
        MethodSpec factory = MethodSpec.methodBuilder("grpcClient").addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.context.annotation", "Bean"))
                .addMember("name", "$S", stubName).build())
            .returns(service.futureStub())
            .addParameter(ClassName.get("org.springframework.grpc.client", "GrpcChannelFactory"), "channels")
            .addParameter(ClassName.get("org.springframework.core.env", "Environment"), "environment")
            .beginControlFlow("try")
            .addStatement("String target = environment.resolveRequiredPlaceholders(environment.getRequiredProperty($S))", property)
            .beginControlFlow("if (target.isBlank())")
            .addStatement("throw new $T($S)", IllegalArgumentException.class, "Target is blank").endControlFlow()
            .addStatement("$T.create(target)", java.net.URI.class)
            .addStatement("return $T.newFutureStub(channels.createChannel($S))", service.serviceClass(), clientName)
            .nextControlFlow("catch ($T failure)", RuntimeException.class)
            .addStatement("throw new $T($S, failure)", IllegalStateException.class,
                "Invalid Spring GRPC target property '" + property + "' for step '" + serviceName + "'")
            .endControlFlow().build();
        return TypeSpec.classBuilder(className + "Configuration").addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.context.annotation", "Configuration"))
                .addMember("proxyBeanMethods", "$L", false).build())
            .addMethod(factory).build();
    }
}
