package org.pipelineframework.processor.renderer;

import java.io.IOException;
import javax.lang.model.element.Modifier;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import org.pipelineframework.processor.ir.OrchestratorBinding;
import org.pipelineframework.processor.ir.PipelineTransport;

/** Generates the only application-specific seam required by the generic AWS Durable host. */
public final class AwsDurableInputDecoderRenderer {
    public static final String CLASS_NAME = "AwsDurablePipelineInputDecoder";
    private static final ClassName APPLICATION_SCOPED =
        ClassName.get("jakarta.enterprise.context", "ApplicationScoped");
    private static final ClassName INJECT = ClassName.get("jakarta.inject", "Inject");
    private static final ClassName OBJECT_MAPPER =
        ClassName.get("com.fasterxml.jackson.databind", "ObjectMapper");
    private static final ClassName JSON_PROCESSING_EXCEPTION =
        ClassName.get("com.fasterxml.jackson.core", "JsonProcessingException");
    private static final ClassName INPUT_DECODER =
        ClassName.get("org.pipelineframework.aws.durable", "AwsDurableInputDecoder");

    public String decoderFqcn(String basePackage) {
        return basePackage + ".orchestrator.service." + CLASS_NAME;
    }

    public void render(OrchestratorBinding binding, GenerationContext context) throws IOException {
        if (binding.inputStreaming()) {
            throw new IllegalArgumentException(
                "AWS_DURABLE coordination currently requires a non-streaming pipeline input");
        }
        ClassName inputDto = CanonicalTransportBindingResolver.resolveAndEnsure(
            context, binding.model(), PipelineTransport.REST).input()
            .map(CanonicalTransportTypeBinding::restDtoType)
            .orElseGet(() -> ClassName.get(
                binding.basePackage() + ".common.dto", binding.inputTypeName() + "Dto"));
        MethodSpec decode = MethodSpec.methodBuilder("decode")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(Object.class)
            .addParameter(String.class, "inputJson")
            .beginControlFlow("try")
            .addStatement("return mapper.readValue(inputJson, $T.class)", inputDto)
            .nextControlFlow("catch ($T malformed)", JSON_PROCESSING_EXCEPTION)
            .addStatement("throw new $T($S, malformed)", IllegalArgumentException.class,
                "AWS Durable pipeline input is not valid JSON for the generated input contract")
            .endControlFlow()
            .build();
        TypeSpec decoder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addAnnotation(AnnotationSpec.builder(APPLICATION_SCOPED).build())
            .addSuperinterface(INPUT_DECODER)
            .addField(FieldSpec.builder(OBJECT_MAPPER, "mapper", Modifier.PRIVATE)
                .addAnnotation(INJECT)
                .build())
            .addMethod(decode)
            .build();
        JavaFile.builder(binding.basePackage() + ".orchestrator.service", decoder)
            .build()
            .writeTo(context.outputDir());
    }
}
