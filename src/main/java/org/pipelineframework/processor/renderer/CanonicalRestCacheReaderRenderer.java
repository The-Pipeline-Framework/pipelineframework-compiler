package org.pipelineframework.processor.renderer;

import com.squareup.javapoet.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Modifier;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateDialect;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.ir.TypeMapping;
import org.pipelineframework.processor.routing.V3JavaTypeResolver;

/** Adapts cached REST values at the existing reader boundary without changing stored representations. */
public final class CanonicalRestCacheReaderRenderer {
    public static final String CLASS_NAME = "CanonicalRestCacheReaderDecorator";

    public void render(PipelineTemplateConfig config, GenerationContext context) throws IOException {
        if (config.dialect() != PipelineTemplateDialect.V3 || context.transportMode() != PipelineTransport.REST) {
            return;
        }
        var resolver = new CanonicalTransportBindingResolver(context);
        var types = new V3JavaTypeResolver(config);
        List<CanonicalTransportTypeBinding> bindings = new ArrayList<>();
        for (String name : config.typeModel().definitions().keySet().stream().sorted().toList()) {
            var javaType = types.resolve(name);
            if (javaType.isEmpty()) continue;
            var mapping = TypeMapping.canonical(javaType.orElseThrow(), name);
            var binding = resolver.resolveAndEnsure(mapping, mapping, PipelineTransport.REST).input();
            if (binding.isEmpty()) continue;
            bindings.add(binding.orElseThrow());
        }
        if (bindings.isEmpty()) return;
        ClassName reader = ClassName.get("org.pipelineframework.cache", "PipelineCacheReader");
        TypeName optionalObject = ParameterizedTypeName.get(ClassName.get(java.util.Optional.class), TypeName.OBJECT);
        var normalize = MethodSpec.methodBuilder("canonicalValue").addModifiers(Modifier.PRIVATE)
            .returns(Object.class).addParameter(Object.class, "value");
        for (var binding : bindings) {
            normalize.beginControlFlow("if (value instanceof $T dto)", binding.restDtoType())
                .addStatement("return new $T().fromExternal(dto)", binding.restMapperType()).endControlFlow();
        }
        normalize.addStatement("return value");
        TypeSpec decorator = TypeSpec.classBuilder(CLASS_NAME).addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("jakarta.decorator", "Decorator"))
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.annotation", "Priority"))
                .addMember("value", "$L", 1000).build())
            .addSuperinterface(reader)
            .addField(FieldSpec.builder(reader, "delegate")
                .addAnnotation(ClassName.get("jakarta.inject", "Inject"))
                .addAnnotation(ClassName.get("jakarta.decorator", "Delegate"))
                .addAnnotation(ClassName.get("jakarta.enterprise.inject", "Any")).build())
            .addMethod(MethodSpec.methodBuilder("get").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(RuntimeSymbols.UNI, optionalObject)).addParameter(String.class, "key")
                .addStatement("return delegate.get(key).onItem().transform(value -> value.map(this::canonicalValue))").build())
            .addMethod(MethodSpec.methodBuilder("exists").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(RuntimeSymbols.UNI, ClassName.get(Boolean.class))).addParameter(String.class, "key")
                .addStatement("return delegate.exists(key)").build())
            .addMethod(MethodSpec.methodBuilder("priority").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .returns(int.class).addStatement("return delegate.priority()").build())
            .addMethod(normalize.build()).build();
        JavaFile.builder(config.basePackage() + ".transport.generated", decorator).build().writeTo(context.outputDir());
    }
}
