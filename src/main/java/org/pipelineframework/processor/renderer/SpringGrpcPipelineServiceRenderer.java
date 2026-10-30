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
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.pipelineframework.processor.ir.GrpcBinding;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.TypeMapping;
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
        Boundary input = boundary(first, first.inputMapping(), inputDomain, requestType, context, true);
        Boundary output = boundary(last, last.outputMapping(), outputDomain, responseType, context, false);

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
            .addStatement("$T input = $L", inputDomain, input.fromExpression("request"))
            .beginControlFlow("pipelineRunner.run(input).whenComplete((value, failure) ->")
            .beginControlFlow("if (failure != null)")
            .addStatement("observer.onError($T.INTERNAL.withCause(failure).asRuntimeException())", statusType)
            .addStatement("return")
            .endControlFlow()
            .beginControlFlow("try")
            .addStatement("observer.onNext($L)", output.toExpression("(" + outputDomain + ") value"))
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

    private Boundary boundary(PipelineStepModel model, TypeMapping mapping, TypeName domain, ClassName proto,
                              GenerationContext context, boolean inbound) throws IOException {
        if (mapping.mapperType().isPresent()) {
            ClassName mapper = mapping.mapperType().filter(ClassName.class::isInstance)
                .map(ClassName.class::cast)
                .orElseThrow(() -> new IllegalStateException("Spring GRPC requires a named mapper class for "
                    + domain + " and " + proto));
            validateMapperPair(mapper, domain, proto, context);
            return new Boundary(domain, proto, mapper, false);
        }
        var normalized = CanonicalTransportBindingResolver.resolveAndEnsure(context,
            model, org.pipelineframework.processor.ir.PipelineTransport.GRPC);
        var generated = inbound ? normalized.input() : normalized.output();
        if (generated.isPresent()) {
            var binding = generated.orElseThrow();
            if (!binding.grpcType().equals(proto)) {
                throw new IllegalStateException("Spring GRPC canonical mapper pair must be <" + domain
                    + ", " + proto + "> but resolved " + binding.grpcType());
            }
            return new Boundary(domain, proto, binding.grpcMapperType(), true);
        }
        if (context.v3GeneratedDomainTypes() && isGeneratedDomainPair(domain, proto,
                context.pipelineBasePackage())) {
            return new Boundary(domain, proto,
                ClassName.get(context.pipelineBasePackage() + ".domain", "PipelineDomainProtoAdapters"), true);
        }
        throw new IllegalStateException("Spring GRPC requires a Mapper<" + domain
            + ", " + proto + "> for the " + (inbound ? "input" : "output") + " boundary");
    }

    private static boolean isGeneratedDomainPair(TypeName domain, ClassName proto, String basePackage) {
        return domain instanceof ClassName javaType
            && javaType.packageName().equals(basePackage + ".domain")
            && proto.packageName().equals(basePackage + ".grpc")
            && proto.enclosingClassName() != null
            && proto.enclosingClassName().simpleName().equals("PipelineTypes")
            && javaType.simpleName().equals(proto.simpleName());
    }

    private static void validateMapperPair(ClassName mapper, TypeName domain, ClassName proto,
                                           GenerationContext context) {
        if (!context.compilerServices().available()) {
            throw new IllegalStateException("Spring GRPC mapper validation requires JSR-269 type services");
        }
        TypeElement element = context.compilerServices().elements().getTypeElement(mapper.canonicalName());
        if (element == null || !hasPair(element.asType(), domain, proto, context)) {
            throw new IllegalStateException("Spring GRPC mapper '" + mapper + "' must implement Mapper<"
                + domain + ", " + proto + ">");
        }
    }

    private static boolean hasPair(TypeMirror type, TypeName domain, ClassName proto,
                                   GenerationContext context) {
        if (type instanceof DeclaredType declared
            && declared.asElement() instanceof TypeElement element
            && element.getQualifiedName().contentEquals("org.pipelineframework.mapper.Mapper")
            && declared.getTypeArguments().size() == 2) {
            return TypeName.get(declared.getTypeArguments().get(0)).equals(domain)
                && TypeName.get(declared.getTypeArguments().get(1)).equals(proto);
        }
        return context.compilerServices().types().directSupertypes(type).stream()
            .anyMatch(parent -> hasPair(parent, domain, proto, context));
    }

    private static void addMapperField(Boundary boundary, String name, TypeSpec.Builder service,
                                       MethodSpec.Builder constructor) {
        if (boundary.generated()) {
            return;
        }
        TypeName mapperType = ParameterizedTypeName.get(ClassName.get("org.pipelineframework.mapper", "Mapper"),
            boundary.domain(), boundary.proto());
        service.addField(FieldSpec.builder(mapperType, name, Modifier.PRIVATE, Modifier.FINAL).build());
        constructor.addParameter(mapperType, name).addStatement("this.$N = $N", name, name);
    }

    private record Boundary(TypeName domain, ClassName proto, ClassName mapper, boolean generated) {
        String fromExpression(String value) {
            return generated ? mapper + ".fromProto(" + value + ")" : "inboundMapper.fromExternal(" + value + ")";
        }

        String toExpression(String value) {
            return generated ? mapper + ".toProto(" + value + ")" : "outboundMapper.toExternal(" + value + ")";
        }
    }
}
