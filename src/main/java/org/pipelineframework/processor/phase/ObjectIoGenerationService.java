package org.pipelineframework.processor.phase;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import com.google.protobuf.DescriptorProtos;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.ir.DeploymentRole;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.renderer.GenerationContext;
import org.pipelineframework.processor.renderer.ObjectIngestInputAdapterRenderer;
import org.pipelineframework.processor.renderer.ObjectSelectionMapperRenderer;
import org.pipelineframework.processor.renderer.TerminalOutputAdapterRenderer;
import org.pipelineframework.processor.util.RoleMetadataGenerator;

/** Coordinates Object Ingest, Object Publish, and grouped selection support generation. */
final class ObjectIoGenerationService {
    private final GenerationPathResolver generationPathResolver;
    private final GenerationPolicy generationPolicy;
    private final ObjectIoGenerationConfigResolver configResolver = new ObjectIoGenerationConfigResolver();
    private final ObjectIoStepResolver stepResolver = new ObjectIoStepResolver();

    ObjectIoGenerationService(GenerationPathResolver generationPathResolver, GenerationPolicy generationPolicy) {
        this.generationPathResolver = generationPathResolver;
        this.generationPolicy = generationPolicy;
    }

    void generateObjectPublishTerminalAdapter(
        PipelineCompilationContext ctx,
        TerminalOutputAdapterRenderer renderer,
        RoleMetadataGenerator roleMetadataGenerator,
        ClassName cacheKeyGenerator,
        DescriptorProtos.FileDescriptorSet descriptorSet
    ) {
        Optional<String> objectPublishConfig = configResolver.objectPublishGenerationConfig(ctx);
        if (objectPublishConfig.isEmpty() || ctx.isTransportModeLocal() || ctx.isPluginHost()) {
            return;
        }
        Optional<PipelineStepModel> terminalModel = stepResolver.terminalBusinessStepWithDeploymentRole(ctx);
        var template = (org.pipelineframework.config.template.PipelineTemplateConfig) ctx.getPipelineTemplateConfig();
        TypeName domainType = ClassName.bestGuess(template.output().object().type());
        DeploymentRole adapterRole = terminalModel
            .map(model -> resolveClientRole(model.deploymentRole()))
            .orElse(DeploymentRole.PIPELINE_SERVER);
        GenerationContext adapterContext = org.pipelineframework.processor.renderer.Jsr269GenerationContext.create(
            ctx.getProcessingEnv(),
            generationPathResolver.resolveRoleOutputDir(ctx, adapterRole),
            adapterRole,
            Set.of(),
            cacheKeyGenerator,
            descriptorSet,
            ctx.getTransportMode(),
            objectPublishConfig.orElseThrow(),
            null,
            true);
        try {
            ClassName generatedClass = renderer.render(
                objectPublishConfig.orElseThrow(), domainType, domainType, Optional.empty(), adapterContext);
            roleMetadataGenerator.recordClassWithRole(
                generatedClass.canonicalName(),
                adapterRole.name());
        } catch (IOException | RuntimeException e) {
            String message = "Failed to generate Object Publish terminal output adapter: " + e.getMessage();
            ctx.getCompilerDiagnostics().error(message);
            throw new RuntimeException(message, e);
        }
    }

    void generateObjectIngestInputAdapter(
        PipelineCompilationContext ctx,
        ObjectIngestInputAdapterRenderer renderer,
        RoleMetadataGenerator roleMetadataGenerator,
        ClassName cacheKeyGenerator,
        DescriptorProtos.FileDescriptorSet descriptorSet
    ) {
        Optional<String> objectIngestConfig = configResolver.objectIngestGenerationConfig(ctx);
        if (objectIngestConfig.isEmpty() || ctx.isTransportModeLocal() || ctx.isPluginHost()) {
            return;
        }
        Optional<PipelineStepModel> firstModel = stepResolver.firstBusinessStepWithDeploymentRole(ctx);
        var template = (org.pipelineframework.config.template.PipelineTemplateConfig) ctx.getPipelineTemplateConfig();
        TypeName domainType = ClassName.bestGuess(template.input().object().type());
        DeploymentRole adapterRole = firstModel
            .map(model -> resolveClientRole(model.deploymentRole()))
            .orElse(DeploymentRole.PIPELINE_SERVER);
        GenerationContext adapterContext = org.pipelineframework.processor.renderer.Jsr269GenerationContext.create(
            ctx.getProcessingEnv(),
            generationPathResolver.resolveRoleOutputDir(ctx, adapterRole),
            adapterRole,
            Set.of(),
            cacheKeyGenerator,
            descriptorSet,
            ctx.getTransportMode(),
            objectIngestConfig.orElseThrow(),
            null,
            true);
        try {
            ClassName generatedClass = renderer.render(
                objectIngestConfig.orElseThrow(), domainType, domainType, Optional.empty(), adapterContext);
            roleMetadataGenerator.recordClassWithRole(
                generatedClass.canonicalName(),
                adapterRole.name());
        } catch (IOException | RuntimeException e) {
            String message = "Failed to generate Object Ingest input adapter: " + e.getMessage();
            ctx.getCompilerDiagnostics().error(message);
            throw new RuntimeException(message, e);
        }
    }

    void generateObjectSelectionMapper(
        PipelineCompilationContext ctx,
        ObjectSelectionMapperRenderer renderer,
        RoleMetadataGenerator roleMetadataGenerator,
        ClassName cacheKeyGenerator,
        DescriptorProtos.FileDescriptorSet descriptorSet
    ) {
        if (!(ctx.getPipelineTemplateConfig() instanceof org.pipelineframework.config.template.PipelineTemplateConfig template)
                || template.version() != 3 || template.input() == null || template.input().object() == null
                || template.input().object().selection().isEmpty() || ctx.isPluginHost()) {
            return;
        }
        var objectInput = template.input().object();
        String localTypeName = objectInput.typeName() == null
            ? ClassName.bestGuess(objectInput.type()).simpleName() : objectInput.typeName();
        var record = template.typeModel().definition(localTypeName)
            .filter(org.pipelineframework.config.template.PipelineTemplateTypeDefinition.RecordType.class::isInstance)
            .map(org.pipelineframework.config.template.PipelineTemplateTypeDefinition.RecordType.class::cast)
            .orElseThrow(() -> new IllegalStateException("Grouped Object Ingest type '" + localTypeName
                + "' must be a v3 record"));
        DeploymentRole adapterRole = stepResolver.firstBusinessStepWithDeploymentRole(ctx)
            .map(model -> resolveClientRole(model.deploymentRole()))
            .orElse(DeploymentRole.PIPELINE_SERVER);
        GenerationContext generationContext = org.pipelineframework.processor.renderer.Jsr269GenerationContext.create(
            ctx.getProcessingEnv(),
            generationPathResolver.resolveRoleOutputDir(ctx, adapterRole),
            adapterRole,
            Set.of(),
            cacheKeyGenerator,
            descriptorSet,
            ctx.getTransportMode(),
            template.basePackage(),
            null,
            true);
        try {
            ClassName generatedClass = renderer.render(template.basePackage(), ClassName.bestGuess(objectInput.type()),
                record, objectInput.selection().orElseThrow(), generationContext);
            roleMetadataGenerator.recordClassWithRole(generatedClass.canonicalName(), adapterRole.name());
        } catch (IOException | RuntimeException e) {
            String message = "Failed to generate Object Selection mapper: " + e.getMessage();
            ctx.getCompilerDiagnostics().error(message);
            throw new RuntimeException(message, e);
        }
    }

    private DeploymentRole resolveClientRole(DeploymentRole serverRole) {
        if (serverRole == null) {
            return DeploymentRole.ORCHESTRATOR_CLIENT;
        }
        DeploymentRole mapped = generationPolicy.resolveClientRole(serverRole);
        return mapped != null ? mapped : DeploymentRole.ORCHESTRATOR_CLIENT;
    }
}
