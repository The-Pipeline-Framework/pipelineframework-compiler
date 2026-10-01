package org.pipelineframework.processor.renderer;

import java.io.IOException;
import javax.lang.model.element.Modifier;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import org.pipelineframework.processor.phase.NamingPolicy;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.ServiceApiKind;
import org.pipelineframework.processor.util.GeneratedServiceTypeResolver;

/**
 * Generates a reactive bridge bean for blocking-authored internal services.
 */
public class BlockingReactiveBridgeRenderer {

    public GenerationTarget target() {
        return GenerationTarget.BLOCKING_REACTIVE_BRIDGE;
    }

    public void render(PipelineStepModel model, GenerationContext ctx) throws IOException {
        TypeSpec bridgeClass = buildBridgeClass(model, ctx.role());
        JavaFile.builder(
                model.servicePackage() + NamingPolicy.PIPELINE_PACKAGE_SUFFIX,
                bridgeClass)
            .build()
            .writeTo(ctx.outputDir());
    }

    private TypeSpec buildBridgeClass(
        PipelineStepModel model,
        org.pipelineframework.processor.ir.DeploymentRole role
    ) {
        TypeName inputType = model.inboundDomainType() != null ? model.inboundDomainType() : ClassName.OBJECT;
        TypeName outputType = model.outboundDomainType() != null ? model.outboundDomainType() : ClassName.OBJECT;

        TypeSpec.Builder builder = TypeSpec.classBuilder(GeneratedServiceTypeResolver.blockingReactiveBridgeClassName(model).simpleName())
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.enterprise.context", "ApplicationScoped")).build())
            .addAnnotation(AnnotationSpec.builder(RuntimeSymbols.UNREMOVABLE).build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.pipelineframework.annotation", "GeneratedRole"))
                .addMember("value", "$T.$L",
                    ClassName.get("org.pipelineframework.annotation", "GeneratedRole", "Role"),
                    role.name())
                .build());

        builder.addSuperinterface(resolveReactiveInterface(model, inputType, outputType));
        builder.addField(FieldSpec.builder(model.serviceClassName(), "blockingService", Modifier.PRIVATE)
            .addAnnotation(AnnotationSpec.builder(RuntimeSymbols.INJECT).build())
            .build());
        builder.addField(FieldSpec.builder(
                ClassName.get("org.pipelineframework.blocking", "BlockingExecutionSupport"),
                "blockingExecutionSupport",
                Modifier.PRIVATE)
            .addAnnotation(AnnotationSpec.builder(RuntimeSymbols.INJECT).build())
            .build());

        boolean useVirtualThreads = model.executionMode() == org.pipelineframework.processor.ir.ExecutionMode.VIRTUAL_THREADS;
        builder.addMethod(buildProcessMethod(model, inputType, outputType, useVirtualThreads));
        if (model.serviceApiKind() == ServiceApiKind.BLOCKING_ITERATOR) {
            ClassName pagedOperation = ClassName.get("org.pipelineframework.paging", "PagedSourceOperation");
            ClassName pagedRequest = ClassName.get("org.pipelineframework.paging", "PagedSourceRequest");
            ClassName pagedStream = ClassName.get("org.pipelineframework.paging", "PagedSourceStream");
            builder.addSuperinterface(ParameterizedTypeName.get(pagedOperation, inputType, outputType));
            builder.addMethod(MethodSpec.methodBuilder("openPage")
                .addAnnotation(Override.class)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "unchecked")
                    .build())
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(pagedStream, outputType))
                .addParameter(ParameterizedTypeName.get(pagedRequest, inputType), "request")
                .beginControlFlow("if (!$T.class.isInstance(this.blockingService))", pagedOperation)
                .addStatement("throw new $T($S)", IllegalStateException.class,
                    "Blocking source does not support paged execution")
                .endControlFlow()
                .addStatement("return (($T<$T, $T>) this.blockingService).openPage(request)", pagedOperation, inputType, outputType)
                .build());
        }
        return builder.build();
    }

    private TypeName resolveReactiveInterface(PipelineStepModel model, TypeName inputType, TypeName outputType) {
        return switch (model.streamingShape()) {
            case UNARY_STREAMING -> ParameterizedTypeName.get(
                RuntimeSymbols.REACTIVE_STREAMING_SERVICE,
                inputType,
                outputType);
            case STREAMING_UNARY -> ParameterizedTypeName.get(
                RuntimeSymbols.REACTIVE_STREAMING_CLIENT_SERVICE,
                inputType,
                outputType);
            case STREAMING_STREAMING -> ParameterizedTypeName.get(
                RuntimeSymbols.REACTIVE_BIDIRECTIONAL_STREAMING_SERVICE,
                inputType,
                outputType);
            default -> ParameterizedTypeName.get(
                RuntimeSymbols.REACTIVE_SERVICE,
                inputType,
                outputType);
        };
    }

    private MethodSpec buildProcessMethod(
        PipelineStepModel model,
        TypeName inputType,
        TypeName outputType,
        boolean useVirtualThreads
    ) {
        if (model.serviceApiKind() == ServiceApiKind.BLOCKING_ITERATOR) {
            return MethodSpec.methodBuilder("process")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(RuntimeSymbols.MULTI, outputType))
                .addParameter(inputType, "processableObj")
                .addStatement(
                    "return blockingExecutionSupport.emitIterator($L, () -> blockingService.iterateBlocking(processableObj))",
                    useVirtualThreads)
                .build();
        }
        return switch (model.streamingShape()) {
            case UNARY_STREAMING -> MethodSpec.methodBuilder("process")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(RuntimeSymbols.MULTI, outputType))
                .addParameter(inputType, "processableObj")
                .addStatement(
                    "return blockingExecutionSupport.emitList($L, () -> blockingService.processBlocking(processableObj))",
                    useVirtualThreads)
                .build();
            case STREAMING_UNARY -> MethodSpec.methodBuilder("process")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(RuntimeSymbols.UNI, outputType))
                .addParameter(ParameterizedTypeName.get(RuntimeSymbols.MULTI, inputType), "processableObj")
                .addStatement(
                    "return processableObj.collect().asList()"
                        + ".onItem().transformToUni(items -> blockingExecutionSupport.supply($L, () -> blockingService.processBlocking(items)))",
                    useVirtualThreads)
                .build();
            case STREAMING_STREAMING -> MethodSpec.methodBuilder("process")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(RuntimeSymbols.MULTI, outputType))
                .addParameter(ParameterizedTypeName.get(RuntimeSymbols.MULTI, inputType), "processableObj")
                .addStatement(
                    "return processableObj.collect().asList()"
                        + ".onItem().transformToMulti(items -> blockingExecutionSupport.emitList($L, () -> blockingService.processBlocking(items)))",
                    useVirtualThreads)
                .build();
            default -> MethodSpec.methodBuilder("process")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(RuntimeSymbols.UNI, outputType))
                .addParameter(inputType, "processableObj")
                .addStatement(
                    "return blockingExecutionSupport.supply($L, () -> blockingService.processBlocking(processableObj))",
                    useVirtualThreads)
                .build();
        };
    }
}
