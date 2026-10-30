package org.pipelineframework.processor.renderer;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import java.io.IOException;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.TypeMapping;

/** Exact domain/protobuf mapper selection shared by Spring gRPC boundaries. */
record SpringGrpcMapperBinding(TypeName domain, ClassName proto, ClassName mapper, boolean generated) {
    static SpringGrpcMapperBinding resolve(PipelineStepModel model, TypeMapping mapping, TypeName domain, ClassName proto,
                              GenerationContext context, boolean inbound) throws IOException {
        if (mapping.mapperType().isPresent()) {
            ClassName mapper = mapping.mapperType().filter(ClassName.class::isInstance)
                .map(ClassName.class::cast)
                .orElseThrow(() -> new IllegalStateException("Spring GRPC requires a named mapper class for "
                    + domain + " and " + proto));
            validateMapperPair(mapper, domain, proto, context);
            return new SpringGrpcMapperBinding(domain, proto, mapper, false);
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
            return new SpringGrpcMapperBinding(domain, proto, binding.grpcMapperType(), true);
        }
        if (context.v3GeneratedDomainTypes() && isGeneratedDomainPair(domain, proto,
                context.pipelineBasePackage())) {
            return new SpringGrpcMapperBinding(domain, proto,
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

    String fromExpression(String value, String field) {
        return generated ? mapper + ".fromProto(" + value + ")" : field + ".fromExternal(" + value + ")";
    }

    String toExpression(String value, String field) {
        return generated ? mapper + ".toProto(" + value + ")" : field + ".toExternal(" + value + ")";
    }
}
