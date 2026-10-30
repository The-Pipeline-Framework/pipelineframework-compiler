package org.pipelineframework.processor.renderer;

import com.google.protobuf.Descriptors;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import javax.lang.model.element.Modifier;
import org.pipelineframework.processor.ir.GrpcBinding;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.phase.NamingPolicy;
import org.pipelineframework.processor.util.GrpcBindingResolver;
import org.pipelineframework.processor.util.GrpcJavaTypeResolver;

/** Generates the Spring bean for the opt-in whole-pipeline unary gRPC boundary. */
public final class SpringGrpcPipelineServiceRenderer {
    public static final String CLASS_NAME = "PipelineGrpcService";

    public void render(PipelineStepModel first, PipelineStepModel last, GenerationContext context)
            throws IOException {
        Descriptors.MethodDescriptor method = new GrpcBindingResolver()
            .resolvePipelineRun(context.descriptorSet());
        var grpcTypes = new GrpcJavaTypeResolver().resolve(
            new GrpcBinding(first, method.getService(), method), context.compilerDiagnostics());
        ClassName requestType = requireType(grpcTypes.grpcParameterType(), "request");
        ClassName responseType = requireType(grpcTypes.grpcReturnType(), "response");
        TypeName inputDomain = requireDomain(first.inboundDomainType(), "first input");
        TypeName outputDomain = requireDomain(last.outboundDomainType(), "final output");
        SpringGrpcMapperBinding input = SpringGrpcMapperBinding.resolve(first, first.inputMapping(), inputDomain, requestType, context, true);
        SpringGrpcMapperBinding output = SpringGrpcMapperBinding.resolve(last, last.outputMapping(), outputDomain, responseType, context, false);

        ClassName runnerType = ClassName.get("org.pipelineframework.runtime.spring", "SpringPipelineRunner");
        ClassName observerType = ClassName.get("io.grpc.stub", "StreamObserver");
        ClassName statusType = ClassName.get("io.grpc", "Status");
        TypeSpec.Builder service = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("org.springframework.stereotype", "Service"))
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.pipelineframework.annotation", "GeneratedRole"))
                .addMember("value", "$T.PIPELINE_SERVER",
                    ClassName.get("org.pipelineframework.annotation", "GeneratedRole", "Role"))
                .build())
            .superclass(ClassName.get(context.pipelineBasePackage() + ".grpc", "PipelineServiceGrpc",
                "PipelineServiceImplBase"))
            .addField(FieldSpec.builder(runnerType, "pipelineRunner", Modifier.PRIVATE, Modifier.FINAL).build());
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
            .addParameter(runnerType, "pipelineRunner")
            .addStatement("this.pipelineRunner = pipelineRunner");
        addMapperField(input, "inboundMapper", service, constructor);
        addMapperField(output, "outboundMapper", service, constructor);
        service.addMethod(constructor.build());

        MethodSpec.Builder run = MethodSpec.methodBuilder("run")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(requestType, "request")
            .addParameter(ParameterizedTypeName.get(observerType, responseType), "observer")
            .beginControlFlow("try")
            .addStatement("$T input = $L", inputDomain, input.fromExpression("request", "inboundMapper"))
            .beginControlFlow("pipelineRunner.run(input).whenComplete((value, failure) ->")
            .beginControlFlow("if (failure != null)")
            .addStatement("observer.onError($T.INTERNAL.withCause(failure).asRuntimeException())", statusType)
            .addStatement("return")
            .endControlFlow()
            .beginControlFlow("try")
            .addStatement("observer.onNext($L)", output.toExpression("(" + outputDomain + ") value", "outboundMapper"))
            .addStatement("observer.onCompleted()")
            .nextControlFlow("catch ($T mappingFailure)", RuntimeException.class)
            .addStatement("observer.onError($T.INTERNAL.withCause(mappingFailure).asRuntimeException())", statusType)
            .endControlFlow()
            .endControlFlow(")")
            .nextControlFlow("catch ($T startupFailure)", RuntimeException.class)
            .addStatement("observer.onError($T.INTERNAL.withCause(startupFailure).asRuntimeException())", statusType)
            .endControlFlow();
        service.addMethod(run.build());
        JavaFile.builder(context.pipelineBasePackage() + NamingPolicy.PIPELINE_PACKAGE_SUFFIX,
            service.build()).build().writeTo(context.outputDir());
    }

    private static ClassName requireType(ClassName type, String label) {
        if (type == null) {
            throw new IllegalStateException("PipelineService.Run is missing its protobuf " + label + " type");
        }
        return type;
    }

    private static TypeName requireDomain(TypeName type, String label) {
        if (type == null) {
            throw new IllegalStateException("Spring GRPC is missing the " + label + " domain type");
        }
        return type;
    }

    private static void addMapperField(SpringGrpcMapperBinding boundary, String name, TypeSpec.Builder service,
                                       MethodSpec.Builder constructor) {
        if (boundary.generated()) {
            return;
        }
        TypeName mapperType = ParameterizedTypeName.get(ClassName.get("org.pipelineframework.mapper", "Mapper"),
            boundary.domain(), boundary.proto());
        service.addField(FieldSpec.builder(mapperType, name, Modifier.PRIVATE, Modifier.FINAL).build());
        constructor.addParameter(boundary.mapper(), name).addStatement("this.$N = $N", name, name);
    }

}
