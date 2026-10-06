/*
 * Copyright (c) 2023-2025 Mariano Barcia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pipelineframework.processor.phase;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for PipelineGenerationPhase */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineGenerationPhaseTest {

    @Mock
    private ProcessingEnvironment processingEnv;

    @Mock
    private RoundEnvironment roundEnv;

    @Mock
    private Messager messager;

    @BeforeEach
    void setUp() {
        when(processingEnv.getMessager()).thenReturn(messager);
        when(processingEnv.getElementUtils())
                .thenReturn(mock(javax.lang.model.util.Elements.class));
        javax.annotation.processing.Filer filer = mock(javax.annotation.processing.Filer.class);
        FileObject fileObject = mock(FileObject.class);
        JavaFileObject sourceFileObject = mock(JavaFileObject.class);
        try {
            when(fileObject.openWriter()).thenReturn(new java.io.StringWriter());
            when(sourceFileObject.openWriter()).thenReturn(new java.io.StringWriter());
            when(filer.createResource(
                any(StandardLocation.class), anyString(), anyString(),
                nullable(javax.lang.model.element.Element[].class)))
                .thenReturn(fileObject);
            when(filer.createResource(any(StandardLocation.class), anyString(), anyString()))
                .thenReturn(fileObject);
            when(filer.createSourceFile(anyString(), any(javax.lang.model.element.Element[].class)))
                .thenReturn(sourceFileObject);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        when(processingEnv.getFiler()).thenReturn(filer);
        when(processingEnv.getSourceVersion()).thenReturn(SourceVersion.RELEASE_21);
    }

    @Test
    void testGenerationPhaseInitialization() {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        assertNotNull(phase);
        assertEquals("Pipeline Generation Phase", phase.name());
    }

    @Test
    void testGenerationPhaseExecutionHandlesEmptyContextGracefully() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));

        // Execute the phase with an empty context (no models)
        // This should not throw exceptions and should handle the empty case
        assertDoesNotThrow(() -> phase.execute(context));
    }

    @Test
    void derivesOuterClassNameFromProtoPath() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("proto/search/baz.proto")
            .build();
        Descriptors.FileDescriptor descriptor = Descriptors.FileDescriptor.buildFrom(
            fileProto,
            new Descriptors.FileDescriptor[0]);

        java.lang.reflect.Method method = PipelineGenerationPhase.class.getDeclaredMethod(
            "deriveOuterClassName",
            Descriptors.FileDescriptor.class);
        method.setAccessible(true);

        String outer = (String) method.invoke(phase, descriptor);
        assertEquals("Baz", outer);
    }

    @Test
    void resolveClientRoleDefaultsToOrchestratorClientWhenNull() {
        ObjectIoGenerationService service = new ObjectIoGenerationService(
            new GenerationPathResolver(), new GenerationPolicy());
        org.pipelineframework.processor.ir.DeploymentRole role = service.resolveClientRole(null);
        assertEquals(org.pipelineframework.processor.ir.DeploymentRole.ORCHESTRATOR_CLIENT, role);
    }

    @Test
    void reportsLegacyTemplateObjectBoundaries() {
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv,
                org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));
        org.pipelineframework.config.template.PipelineTemplateConfig template =
            mock(org.pipelineframework.config.template.PipelineTemplateConfig.class);
        when(template.version()).thenReturn(2);
        when(template.input()).thenReturn(new org.pipelineframework.config.boundary.PipelineInputBoundaryConfig(
            null, new org.pipelineframework.config.boundary.PipelineObjectInputConfig(
                "input-files", "com.example.Input", "Input", "com.example.InputMapper")));
        when(template.output()).thenReturn(new org.pipelineframework.config.boundary.PipelineOutputBoundaryConfig(
            null, new org.pipelineframework.config.boundary.PipelineObjectOutputConfig(
                "output-files", "com.example.Output", "Output", "com.example.OutputMapper")));
        context.setPipelineTemplateConfig(template);

        ObjectIoGenerationConfigResolver resolver = new ObjectIoGenerationConfigResolver();
        assertTrue(resolver.objectIngestGenerationConfig(context).isEmpty());
        assertTrue(resolver.objectPublishGenerationConfig(context).isEmpty());
        verify(messager).printMessage(javax.tools.Diagnostic.Kind.ERROR,
            "Object Ingest requires a v3 pipeline template");
        verify(messager).printMessage(javax.tools.Diagnostic.Kind.ERROR,
            "Object Publish requires a v3 pipeline template");
    }

    @Test
    void reportsEffectiveObjectBoundariesWithoutATemplate() {
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv,
                org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));
        org.pipelineframework.config.pipeline.PipelineYamlConfig effective =
            mock(org.pipelineframework.config.pipeline.PipelineYamlConfig.class);
        when(effective.input()).thenReturn(new org.pipelineframework.config.boundary.PipelineInputBoundaryConfig(
            null, new org.pipelineframework.config.boundary.PipelineObjectInputConfig(
                "input-files", "com.example.Input", "Input", "com.example.InputMapper")));
        when(effective.output()).thenReturn(new org.pipelineframework.config.boundary.PipelineOutputBoundaryConfig(
            null, new org.pipelineframework.config.boundary.PipelineObjectOutputConfig(
                "output-files", "com.example.Output", "Output", "com.example.OutputMapper")));
        context.setEffectivePipelineConfig(effective);

        ObjectIoGenerationConfigResolver resolver = new ObjectIoGenerationConfigResolver();
        assertTrue(resolver.objectIngestGenerationConfig(context).isEmpty());
        assertTrue(resolver.objectPublishGenerationConfig(context).isEmpty());
        verify(messager).printMessage(javax.tools.Diagnostic.Kind.ERROR,
            "Object Ingest requires a v3 pipeline template");
        verify(messager).printMessage(javax.tools.Diagnostic.Kind.ERROR,
            "Object Publish requires a v3 pipeline template");
    }

    @Test
    void selectsV3BoundaryRolesWithoutLegacyMapperMetadata() throws Exception {
        ObjectIoStepResolver stepResolver = new ObjectIoStepResolver();
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));
        org.pipelineframework.processor.ir.PipelineStepModel first = model("First", org.pipelineframework.processor.ir.DeploymentRole.PIPELINE_SERVER, false);
        org.pipelineframework.processor.ir.PipelineStepModel terminal = model("Terminal", org.pipelineframework.processor.ir.DeploymentRole.REST_SERVER, false);
        context.setStepModels(List.of(
            model("Observer", org.pipelineframework.processor.ir.DeploymentRole.PIPELINE_SERVER, true),
            first,
            terminal));

        java.util.Optional<org.pipelineframework.processor.ir.PipelineStepModel> selectedFirst =
            stepResolver.firstBusinessStepWithDeploymentRole(context);
        java.util.Optional<org.pipelineframework.processor.ir.PipelineStepModel> selectedTerminal =
            stepResolver.terminalBusinessStepWithDeploymentRole(context);

        assertEquals(first, selectedFirst.orElseThrow());
        assertEquals(terminal, selectedTerminal.orElseThrow());
        assertTrue(selectedFirst.orElseThrow().inputMapping().mapperType().isEmpty());
        assertTrue(selectedTerminal.orElseThrow().outputMapping().mapperType().isEmpty());
    }

    private static org.pipelineframework.processor.ir.PipelineStepModel model(
        String serviceName,
        org.pipelineframework.processor.ir.DeploymentRole deploymentRole,
        boolean sideEffect
    ) {
        return new org.pipelineframework.processor.ir.PipelineStepModel.Builder()
            .serviceName(serviceName)
            .generatedName(serviceName)
            .servicePackage("com.example")
            .serviceClassName(com.squareup.javapoet.ClassName.get("com.example", serviceName))
            .inputMapping(org.pipelineframework.processor.ir.TypeMapping.withoutMapper(
                com.squareup.javapoet.ClassName.get("com.example", serviceName + "Input")))
            .outputMapping(org.pipelineframework.processor.ir.TypeMapping.withoutMapper(
                com.squareup.javapoet.ClassName.get("com.example", serviceName + "Output")))
            .streamingShape(org.pipelineframework.processor.ir.StreamingShape.UNARY_UNARY)
            .enabledTargets(Set.of(org.pipelineframework.processor.ir.GenerationTarget.CLIENT_STEP))
            .executionMode(org.pipelineframework.processor.ir.ExecutionMode.DEFAULT)
            .deploymentRole(deploymentRole)
            .sideEffect(sideEffect)
            .orderingRequirement(org.pipelineframework.parallelism.OrderingRequirement.RELAXED)
            .threadSafety(org.pipelineframework.parallelism.ThreadSafety.SAFE)
            .build();
    }

    @Test
    void skipsClientStepGenerationWhenGrpcBindingMissing() {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));

        org.pipelineframework.processor.ir.PipelineStepModel model =
            new org.pipelineframework.processor.ir.PipelineStepModel.Builder()
                .serviceName("ProcessMissingBindingService")
                .generatedName("ProcessMissingBindingService")
                .servicePackage("com.example")
                .serviceClassName(com.squareup.javapoet.ClassName.get("com.example", "MissingBindingService"))
                .inputMapping(org.pipelineframework.processor.ir.TypeMapping.withoutMapper(
                    com.squareup.javapoet.ClassName.get("com.example", "In")))
                .outputMapping(org.pipelineframework.processor.ir.TypeMapping.withoutMapper(
                    com.squareup.javapoet.ClassName.get("com.example", "Out")))
                .streamingShape(org.pipelineframework.processor.ir.StreamingShape.UNARY_UNARY)
                .enabledTargets(java.util.Set.of(org.pipelineframework.processor.ir.GenerationTarget.CLIENT_STEP))
                .executionMode(org.pipelineframework.processor.ir.ExecutionMode.DEFAULT)
                .deploymentRole(org.pipelineframework.processor.ir.DeploymentRole.ORCHESTRATOR_CLIENT)
                .sideEffect(false)
                .orderingRequirement(org.pipelineframework.parallelism.OrderingRequirement.RELAXED)
                .threadSafety(org.pipelineframework.parallelism.ThreadSafety.SAFE)
                .build();

        context.setStepModels(java.util.List.of(model));
        context.setRendererBindings(java.util.Map.of());

        assertDoesNotThrow(() -> phase.execute(context));
    }

    @Test
    void skipsObjectIoBoundaryAdaptersForPluginHostModules() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));
        org.pipelineframework.config.template.PipelineTemplateConfig template =
            mock(org.pipelineframework.config.template.PipelineTemplateConfig.class);
        when(template.version()).thenReturn(3);
        when(template.basePackage()).thenReturn("com.example");
        when(template.input()).thenReturn(new org.pipelineframework.config.boundary.PipelineInputBoundaryConfig(
            null, new org.pipelineframework.config.boundary.PipelineObjectInputConfig(
                "input-files", "com.example.Input", "Input", "com.example.InputMapper")));
        when(template.output()).thenReturn(new org.pipelineframework.config.boundary.PipelineOutputBoundaryConfig(
            null, new org.pipelineframework.config.boundary.PipelineObjectOutputConfig(
                "output-files", "com.example.Output", "Output", "com.example.OutputMapper")));
        context.setPipelineTemplateConfig(template);
        context.setPluginHost(true);
        context.setGeneratedSourcesRoot(Path.of("target/generated-sources-test"));
        context.setRendererBindings(java.util.Map.of());
        context.setStepModels(java.util.List.of());

        assertDoesNotThrow(() -> phase.execute(context));
        javax.annotation.processing.Filer filer = processingEnv.getFiler();
        verify(filer, never()).createSourceFile(eq("com.example.pipeline.ObjectIngestPipelineInputAdapter"),
            any(javax.lang.model.element.Element[].class));
        verify(filer, never()).createSourceFile(eq("com.example.pipeline.ObjectPublishTerminalOutputAdapter"),
            any(javax.lang.model.element.Element[].class));
    }

    @Test
    void generatesV3ObjectAdaptersAndServiceDescriptors(@TempDir Path tempDir) throws Exception {
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv,
                org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));
        org.pipelineframework.config.template.PipelineTemplateConfig template =
            mock(org.pipelineframework.config.template.PipelineTemplateConfig.class);
        when(template.version()).thenReturn(3);
        when(template.basePackage()).thenReturn("com.example");
        when(template.input()).thenReturn(new org.pipelineframework.config.boundary.PipelineInputBoundaryConfig(
            null, new org.pipelineframework.config.boundary.PipelineObjectInputConfig(
                "input-files", "com.example.Input", "Input", "com.example.InputMapper")));
        when(template.output()).thenReturn(new org.pipelineframework.config.boundary.PipelineOutputBoundaryConfig(
            null, new org.pipelineframework.config.boundary.PipelineObjectOutputConfig(
                "output-files", "com.example.Output", "Output", "com.example.OutputMapper")));
        context.setPipelineTemplateConfig(template);
        context.setTransportMode(org.pipelineframework.processor.ir.PipelineTransport.REST);
        context.setGeneratedSourcesRoot(tempDir.resolve("generated-sources"));
        context.setStepModels(List.of(model("First", org.pipelineframework.processor.ir.DeploymentRole.REST_SERVER, false)));
        org.pipelineframework.processor.util.RoleMetadataGenerator roles =
            mock(org.pipelineframework.processor.util.RoleMetadataGenerator.class);
        ObjectIoGenerationService service = new ObjectIoGenerationService(
            new GenerationPathResolver(), new GenerationPolicy());
        javax.annotation.processing.Filer filer = processingEnv.getFiler();
        java.io.StringWriter ingestDescriptor = new java.io.StringWriter();
        java.io.StringWriter publishDescriptor = new java.io.StringWriter();
        FileObject ingestResource = mock(FileObject.class);
        FileObject publishResource = mock(FileObject.class);
        when(ingestResource.openWriter()).thenReturn(ingestDescriptor);
        when(publishResource.openWriter()).thenReturn(publishDescriptor);
        when(filer.createResource(StandardLocation.CLASS_OUTPUT, "",
            "META-INF/services/org.pipelineframework.objectingest.ObjectIngestInputAdapter"))
            .thenReturn(ingestResource);
        when(filer.createResource(StandardLocation.CLASS_OUTPUT, "",
            "META-INF/services/org.pipelineframework.objectpublish.TerminalOutputAdapter"))
            .thenReturn(publishResource);

        service.generateObjectIngestInputAdapter(context,
            new org.pipelineframework.processor.renderer.ObjectIngestInputAdapterRenderer(), roles, null, null);
        service.generateObjectPublishTerminalAdapter(context,
            new org.pipelineframework.processor.renderer.TerminalOutputAdapterRenderer(), roles, null, null);

        verify(filer).createSourceFile(eq("com.example.pipeline.ObjectIngestPipelineInputAdapter"),
            any(javax.lang.model.element.Element[].class));
        verify(filer).createSourceFile(eq("com.example.pipeline.ObjectPublishTerminalOutputAdapter"),
            any(javax.lang.model.element.Element[].class));
        verify(filer).createResource(StandardLocation.CLASS_OUTPUT, "",
            "META-INF/services/org.pipelineframework.objectingest.ObjectIngestInputAdapter");
        verify(filer).createResource(StandardLocation.CLASS_OUTPUT, "",
            "META-INF/services/org.pipelineframework.objectpublish.TerminalOutputAdapter");
        verify(roles).recordClassWithRole("com.example.pipeline.ObjectIngestPipelineInputAdapter",
            org.pipelineframework.processor.ir.DeploymentRole.ORCHESTRATOR_CLIENT.name());
        verify(roles).recordClassWithRole("com.example.pipeline.ObjectPublishTerminalOutputAdapter",
            org.pipelineframework.processor.ir.DeploymentRole.ORCHESTRATOR_CLIENT.name());
        assertEquals("com.example.pipeline.ObjectIngestPipelineInputAdapter" + System.lineSeparator(),
            ingestDescriptor.toString());
        assertEquals("com.example.pipeline.ObjectPublishTerminalOutputAdapter" + System.lineSeparator(),
            publishDescriptor.toString());
    }

    @Test
    void externalAdapterGenerationContextPropagatesEnabledAspects() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));
        context.setGeneratedSourcesRoot(Path.of("target/generated-sources-test"));

        java.lang.reflect.Method method = PipelineGenerationPhase.class.getDeclaredMethod(
            "createExternalAdapterGenerationContext",
            org.pipelineframework.processor.PipelineCompilationContext.class,
            org.pipelineframework.processor.ir.DeploymentRole.class,
            Set.class,
            com.squareup.javapoet.ClassName.class,
            com.google.protobuf.DescriptorProtos.FileDescriptorSet.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        org.pipelineframework.processor.renderer.GenerationContext generationContext =
            (org.pipelineframework.processor.renderer.GenerationContext) method.invoke(
                phase,
                context,
                org.pipelineframework.processor.ir.DeploymentRole.PIPELINE_SERVER,
                Set.of("cache", "audit"),
                null,
                null);

        assertEquals(Set.of("cache", "audit"), generationContext.enabledAspects());
        assertEquals(org.pipelineframework.processor.ir.DeploymentRole.PIPELINE_SERVER, generationContext.role());
    }

    @Test
    void generatesAwsDurableDecoderForPipelineServerHost(@TempDir Path tempDir) throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(
                processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));
        context.setGeneratedSourcesRoot(tempDir.resolve("generated-sources-test"));
        context.setCoordinationHost(org.pipelineframework.processor.ir.CoordinationHost.AWS_DURABLE);
        context.setPlatformMode(org.pipelineframework.config.PlatformMode.FUNCTION);
        var type = com.squareup.javapoet.ClassName.get("com.example.common.domain", "Order");
        var model = new org.pipelineframework.processor.ir.PipelineStepModel(
            "OrchestratorService", "OrchestratorService", "com.example.orchestrator.service",
            com.squareup.javapoet.ClassName.get("com.example.orchestrator.service", "OrchestratorService"),
            org.pipelineframework.processor.ir.TypeMapping.canonical(type, "Order"),
            org.pipelineframework.processor.ir.TypeMapping.canonical(type, "Order"),
            org.pipelineframework.processor.ir.StreamingShape.UNARY_UNARY,
            Set.of(org.pipelineframework.processor.ir.GenerationTarget.GRPC_SERVICE),
            org.pipelineframework.processor.ir.ExecutionMode.DEFAULT,
            org.pipelineframework.processor.ir.DeploymentRole.ORCHESTRATOR_CLIENT, false, null);
        var binding = new org.pipelineframework.processor.ir.OrchestratorBinding(
            model, "com.example", "REST", "Order", "Order", false, false,
            "ProcessOrderService", org.pipelineframework.processor.ir.StreamingShape.UNARY_UNARY,
            null, null, null);
        var roles = org.mockito.Mockito.mock(org.pipelineframework.processor.util.RoleMetadataGenerator.class);
        java.lang.reflect.Method method = PipelineGenerationPhase.class.getDeclaredMethod(
            "generateAwsDurableInputDecoder",
            org.pipelineframework.processor.PipelineCompilationContext.class,
            org.pipelineframework.processor.ir.OrchestratorBinding.class,
            org.pipelineframework.processor.renderer.AwsDurableInputDecoderRenderer.class,
            org.pipelineframework.processor.util.RoleMetadataGenerator.class,
            com.squareup.javapoet.ClassName.class,
            com.google.protobuf.DescriptorProtos.FileDescriptorSet.class);
        method.setAccessible(true);

        method.invoke(phase, context, binding,
            new org.pipelineframework.processor.renderer.AwsDurableInputDecoderRenderer(), roles, null, null);

        assertTrue(Files.exists(tempDir.resolve(
            "generated-sources-test/pipeline-server/com/example/orchestrator/service/AwsDurablePipelineInputDecoder.java")));
        assertFalse(Files.exists(tempDir.resolve(
            "generated-sources-test/rest-server/com/example/orchestrator/service/AwsDurablePipelineInputDecoder.java")));
        org.mockito.Mockito.verify(roles).recordClassWithRole(
            "com.example.orchestrator.service.AwsDurablePipelineInputDecoder", "PIPELINE_SERVER");
    }

    @Test
    void computesEnabledAspectsFromContext() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));

        org.pipelineframework.processor.ir.PipelineAspectModel aspect1 =
            new org.pipelineframework.processor.ir.PipelineAspectModel(
                "Cache",
                org.pipelineframework.processor.ir.AspectScope.GLOBAL,
                org.pipelineframework.processor.ir.AspectPosition.AFTER_STEP,
                java.util.Map.of());
        org.pipelineframework.processor.ir.PipelineAspectModel aspect2 =
            new org.pipelineframework.processor.ir.PipelineAspectModel(
                "Persistence",
                org.pipelineframework.processor.ir.AspectScope.GLOBAL,
                org.pipelineframework.processor.ir.AspectPosition.AFTER_STEP,
                java.util.Map.of());

        context.setAspectModels(java.util.List.of(aspect1, aspect2));

        java.lang.reflect.Method method = PipelineGenerationPhase.class.getDeclaredMethod(
            "computeEnabledAspects",
            org.pipelineframework.processor.PipelineCompilationContext.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<String> enabledAspects = (Set<String>) method.invoke(phase, context);

        assertEquals(Set.of("cache", "persistence"), enabledAspects);
    }

    @Test
    void handlesNullAspectModelsWhenComputingEnabledAspects() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));
        context.setAspectModels(null);

        java.lang.reflect.Method method = PipelineGenerationPhase.class.getDeclaredMethod(
            "computeEnabledAspects",
            org.pipelineframework.processor.PipelineCompilationContext.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<String> enabledAspects = (Set<String>) method.invoke(phase, context);

        assertTrue(enabledAspects.isEmpty());
    }

    @Test
    void resolvesCacheKeyGeneratorFromOptions() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        when(processingEnv.getOptions()).thenReturn(java.util.Map.of(
            "pipeline.cache.keyGenerator", "com.example.CustomKeyGenerator"
        ));

        java.lang.reflect.Method method = PipelineGenerationPhase.class.getDeclaredMethod(
            "resolveCacheKeyGenerator",
            org.pipelineframework.processor.PipelineCompilationContext.class);
        method.setAccessible(true);

        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));

        @SuppressWarnings("unchecked")
        java.util.Optional<com.squareup.javapoet.ClassName> keyGenerator =
            (java.util.Optional<com.squareup.javapoet.ClassName>) method.invoke(phase, context);

        assertTrue(keyGenerator.isPresent());
        assertEquals("CustomKeyGenerator", keyGenerator.orElseThrow().simpleName());
        assertEquals("com.example", keyGenerator.orElseThrow().packageName());
    }

    @Test
    void returnsNullWhenCacheKeyGeneratorNotConfigured() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        when(processingEnv.getOptions()).thenReturn(java.util.Map.of());

        java.lang.reflect.Method method = PipelineGenerationPhase.class.getDeclaredMethod(
            "resolveCacheKeyGenerator",
            org.pipelineframework.processor.PipelineCompilationContext.class);
        method.setAccessible(true);

        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));

        @SuppressWarnings("unchecked")
        java.util.Optional<com.squareup.javapoet.ClassName> keyGenerator =
            (java.util.Optional<com.squareup.javapoet.ClassName>) method.invoke(phase, context);

        assertTrue(keyGenerator.isEmpty());
    }

    @Test
    void generatesOrchestratorArtifactsWhenEnabled() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));
        context.setOrchestratorGenerated(true);
        context.setGeneratedSourcesRoot(Path.of("target/generated-sources-test"));

        org.pipelineframework.processor.ir.PipelineStepModel model =
            new org.pipelineframework.processor.ir.PipelineStepModel.Builder()
                .serviceName("OrchestratorService")
                .generatedName("OrchestratorService")
                .servicePackage("com.example.orchestrator.service")
                .serviceClassName(com.squareup.javapoet.ClassName.get("com.example.orchestrator.service", "OrchestratorService"))
                .inputMapping(new org.pipelineframework.processor.ir.TypeMapping(
                    com.squareup.javapoet.ClassName.get("com.example", "InputDto"), null, false))
                .outputMapping(new org.pipelineframework.processor.ir.TypeMapping(
                    com.squareup.javapoet.ClassName.get("com.example", "OutputDto"), null, false))
                .streamingShape(org.pipelineframework.processor.ir.StreamingShape.UNARY_UNARY)
                .enabledTargets(java.util.Set.of(org.pipelineframework.processor.ir.GenerationTarget.GRPC_SERVICE))
                .executionMode(org.pipelineframework.processor.ir.ExecutionMode.DEFAULT)
                .deploymentRole(org.pipelineframework.processor.ir.DeploymentRole.ORCHESTRATOR_CLIENT)
                .sideEffect(false)
                .cacheKeyGenerator(null)
                .orderingRequirement(org.pipelineframework.parallelism.OrderingRequirement.RELAXED)
                .threadSafety(org.pipelineframework.parallelism.ThreadSafety.SAFE)
                .build();

        org.pipelineframework.processor.ir.OrchestratorBinding binding =
            new org.pipelineframework.processor.ir.OrchestratorBinding(
                model,
                "com.example",
                "GRPC",
                "Input",
                "Output",
                false,
                false,
                "ProcessFirstService",
                org.pipelineframework.processor.ir.StreamingShape.UNARY_UNARY,
                null,
                null,
                null
            );

        context.setRendererBindings(java.util.Map.of("orchestrator", binding));
        context.setStepModels(java.util.List.of(model));
        context.setDescriptorSet(buildMinimalOrchestratorDescriptorSet());

        assertDoesNotThrow(() -> phase.execute(context));
    }

    private static DescriptorProtos.FileDescriptorSet buildMinimalOrchestratorDescriptorSet() {
        DescriptorProtos.FileDescriptorProto proto = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("orchestrator.proto")
            .setPackage("com.example.grpc")
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Input"))
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Output"))
            .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("OrchestratorService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                    .setName("Run")
                    .setInputType(".com.example.grpc.Input")
                    .setOutputType(".com.example.grpc.Output")))
            .build();
        return DescriptorProtos.FileDescriptorSet.newBuilder().addFile(proto).build();
    }

    @Test
    void derivesOuterClassNameWithCustomJavaOuterClassname() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("service.proto")
            .setOptions(DescriptorProtos.FileOptions.newBuilder()
                .setJavaOuterClassname("CustomOuterClass")
                .build())
            .build();
        Descriptors.FileDescriptor descriptor = Descriptors.FileDescriptor.buildFrom(
            fileProto,
            new Descriptors.FileDescriptor[0]);

        java.lang.reflect.Method method = PipelineGenerationPhase.class.getDeclaredMethod(
            "deriveOuterClassName",
            Descriptors.FileDescriptor.class);
        method.setAccessible(true);

        String outer = (String) method.invoke(phase, descriptor);
        assertEquals("CustomOuterClass", outer);
    }

    @Test
    void derivesOuterClassNameHandlingComplexFilePaths() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("path/to/deeply/nested-file_name.proto")
            .build();
        Descriptors.FileDescriptor descriptor = Descriptors.FileDescriptor.buildFrom(
            fileProto,
            new Descriptors.FileDescriptor[0]);

        java.lang.reflect.Method method = PipelineGenerationPhase.class.getDeclaredMethod(
            "deriveOuterClassName",
            Descriptors.FileDescriptor.class);
        method.setAccessible(true);

        String outer = (String) method.invoke(phase, descriptor);
        assertEquals("NestedFileName", outer);
    }

    @Test
    void skipsSideEffectBeanGenerationWhenAlreadyGenerated() throws Exception {
        PipelineGenerationPhase phase = new PipelineGenerationPhase();
        org.pipelineframework.processor.PipelineCompilationContext context =
            new org.pipelineframework.processor.PipelineCompilationContext(processingEnv, org.pipelineframework.processor.Jsr269SourceInventoryTestSupport.snapshot(roundEnv));

        org.pipelineframework.processor.ir.PipelineStepModel model =
            new org.pipelineframework.processor.ir.PipelineStepModel.Builder()
                .serviceName("ProcessSideEffectService")
                .generatedName("ProcessSideEffectService")
                .servicePackage("com.example")
                .serviceClassName(com.squareup.javapoet.ClassName.get("com.example", "SideEffectService"))
                .inputMapping(new org.pipelineframework.processor.ir.TypeMapping(
                    com.squareup.javapoet.ClassName.get("com.example", "Input"), null, false))
                .outputMapping(new org.pipelineframework.processor.ir.TypeMapping(
                    com.squareup.javapoet.ClassName.get("com.example", "Output"), null, false))
                .streamingShape(org.pipelineframework.processor.ir.StreamingShape.UNARY_UNARY)
                .enabledTargets(java.util.Set.of(org.pipelineframework.processor.ir.GenerationTarget.GRPC_SERVICE))
                .executionMode(org.pipelineframework.processor.ir.ExecutionMode.DEFAULT)
                .deploymentRole(org.pipelineframework.processor.ir.DeploymentRole.PIPELINE_SERVER)
                .sideEffect(true)
                .cacheKeyGenerator(null)
                .orderingRequirement(org.pipelineframework.parallelism.OrderingRequirement.RELAXED)
                .threadSafety(org.pipelineframework.parallelism.ThreadSafety.SAFE)
                .build();

        context.setStepModels(java.util.List.of(model));
        context.setRendererBindings(java.util.Map.of());

        assertDoesNotThrow(() -> phase.execute(context));
    }
}
