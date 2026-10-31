package org.pipelineframework.processor;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.protobuf.DescriptorProtos;
import com.squareup.javapoet.ClassName;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.pipelineframework.processor.composition.PipelineReference;
import org.pipelineframework.processor.ir.*;
import org.pipelineframework.processor.util.GrpcBindingResolver;

import static org.junit.jupiter.api.Assertions.*;

class AspectExpansionProcessorTest {

    @Test
    void rejectsSharedObserverNamesForDifferentDomainTypes() throws Exception {
        ResolvedStep original = resolvedStep("ProcessFolderService");
        for (String definition : List.of("$root", "child")) {
            PipelineStepModel conflicting = original.model().toBuilder()
                .definition(new PipelineReference(definition))
                .inputMapping(new TypeMapping(ClassName.get("other.domain", "CsvFolder"),
                    original.model().inputMapping().mapperType().orElseThrow(), true))
                .outputMapping(new TypeMapping(ClassName.get("other.domain", "CsvPaymentsInputFile"),
                    original.model().outputMapping().mapperType().orElseThrow(), true))
                .build();
            ResolvedStep other = new ResolvedStep(conflicting, original.grpcBinding(), original.restBinding());
            for (AspectPosition position : AspectPosition.values()) {
                PipelineAspectModel aspect = new PipelineAspectModel("persistence", AspectScope.GLOBAL, position,
                    aspectConfig("org.pipelineframework.plugin.persistence.PersistenceService"));
                var error = assertThrows(IllegalStateException.class, () -> new AspectExpansionProcessor()
                    .expandAspects(List.of(original, other), List.of(aspect)));
                assertTrue(error.getMessage().contains("different domain types"), error.getMessage());
                assertTrue(error.getMessage().contains("other.domain"), error.getMessage());
                assertTrue(error.getMessage().contains("org.pipelineframework.csv.common.domain"), error.getMessage());
            }
        }
    }

    @Test
    void rejectsNormalisedObserverNamesWithDifferentPluginImplementations() throws Exception {
        ResolvedStep original = resolvedStep("ProcessFolderService");
        ResolvedStep child = new ResolvedStep(original.model().toBuilder()
            .serviceName("ProcessOtherFolderService").definition(new PipelineReference("child")).build(),
            original.grpcBinding(), original.restBinding());
        var first = aspectConfig("com.first.AuditPlugin");
        first.put("targetSteps", List.of(original.model().serviceName()));
        var second = aspectConfig("com.second.AuditPlugin");
        second.put("targetSteps", List.of(child.model().serviceName()));
        for (AspectPosition position : AspectPosition.values()) {
            var aspects = List.of(
                new PipelineAspectModel("audit-log", AspectScope.STEPS, position, first),
                new PipelineAspectModel("audit_log", AspectScope.STEPS, position, second));
            var error = assertThrows(IllegalStateException.class, () -> new AspectExpansionProcessor()
                .expandAspects(List.of(original, child), aspects));
            assertTrue(error.getMessage().contains("different plugin classes"), error.getMessage());
            assertTrue(error.getMessage().contains("com.first.AuditPlugin"), error.getMessage());
            assertTrue(error.getMessage().contains("com.second.AuditPlugin"), error.getMessage());
        }
    }

    @Test
    void preservesObserverOccurrenceInEachDefinitionWhileReusingImplementation() throws Exception {
        ResolvedStep original = resolvedStep("ProcessFolderService");
        PipelineReference root = new PipelineReference("$root");
        PipelineReference child = new PipelineReference("child");
        ResolvedStep rootStep = new ResolvedStep(original.model().toBuilder().definition(root).build(),
            original.grpcBinding(), original.restBinding());
        ResolvedStep childStep = new ResolvedStep(original.model().toBuilder().definition(child).build(),
            original.grpcBinding(), original.restBinding());
        for (AspectPosition position : AspectPosition.values()) {
            PipelineAspectModel aspect = new PipelineAspectModel("persistence", AspectScope.GLOBAL, position,
                aspectConfig("org.pipelineframework.plugin.persistence.PersistenceService"));
            List<ResolvedStep> expanded = new AspectExpansionProcessor().expandAspects(
                List.of(rootStep, childStep), List.of(aspect));
            List<PipelineStepModel> observers = expanded.stream().map(ResolvedStep::model)
                .filter(PipelineStepModel::sideEffect).toList();
            assertEquals(List.of(root, child), observers.stream().map(PipelineStepModel::definition).toList());
            assertEquals(observers.getFirst().serviceName(), observers.getLast().serviceName());
            assertEquals(2, expanded.stream().filter(step -> root.equals(step.model().definition())).count());
            assertEquals(2, expanded.stream().filter(step -> child.equals(step.model().definition())).count());
        }
    }

    @Test
    void expandsGlobalAspectsAfterSteps() throws Exception {
        PipelineReference definition = new PipelineReference("deployment-lifecycle");
        ResolvedStep original = resolvedStep("ProcessFolderService");
        ResolvedStep step = new ResolvedStep(
            original.model().toBuilder().definition(definition).build(),
            original.grpcBinding(),
            original.restBinding());
        PipelineAspectModel aspect = new PipelineAspectModel(
            "persistence",
            AspectScope.GLOBAL,
            AspectPosition.AFTER_STEP,
            aspectConfig("org.pipelineframework.plugin.persistence.PersistenceService")
        );

        AspectExpansionProcessor processor = new AspectExpansionProcessor();
        List<ResolvedStep> expanded = processor.expandAspects(
            List.of(step),
            List.of(aspect)
        );

        assertEquals(2, expanded.size(), "Expected original step and one synthetic step");
        assertEquals(step.model().serviceName(), expanded.get(0).model().serviceName());
        assertTrue(expanded.get(1).model().generatedName().contains("Persistence"));
        assertEquals(definition, expanded.get(1).model().definition());
        assertEquals("ObservePersistenceCsvPaymentsInputFileSideEffectService", expanded.get(1).model().serviceName());
    }

    @SneakyThrows
    @Test
    void rejectsStepScopedAspectTargetingMissingStep() {
        ResolvedStep step = resolvedStep("ProcessFolderService");
        ResolvedStep extraStep = resolvedStepWithoutBinding("ProcessPaymentsService");
        Map<String, Object> config = aspectConfig("org.pipelineframework.plugin.persistence.PersistenceService");
        config.put("targetSteps", List.of("MissingStep"));

        PipelineAspectModel stepScoped = new PipelineAspectModel(
            "persistence",
            AspectScope.STEPS,
            AspectPosition.AFTER_STEP,
            config
        );

        AspectExpansionProcessor processor = new AspectExpansionProcessor();
        assertThrows(IllegalArgumentException.class, () -> processor.expandAspects(
            List.of(step, extraStep),
            List.of(stepScoped)
        ));
    }

    @SneakyThrows
    @Test
    void appliesStepScopedAspectWhenTargetStepsProvidedAsList() {
        ResolvedStep step = resolvedStep("ProcessFolderService");
        Map<String, Object> config = aspectConfig("org.pipelineframework.plugin.persistence.PersistenceService");
        config.put("targetSteps", List.of("ProcessFolderService"));

        PipelineAspectModel stepScoped = new PipelineAspectModel(
            "persistence",
            AspectScope.STEPS,
            AspectPosition.AFTER_STEP,
            config
        );

        AspectExpansionProcessor processor = new AspectExpansionProcessor();
        List<ResolvedStep> expanded = processor.expandAspects(
            List.of(step),
            List.of(stepScoped)
        );

        assertEquals(2, expanded.size(), "Expected original step and one synthetic step");
        assertEquals(step.model().serviceName(), expanded.get(0).model().serviceName());
        assertTrue(expanded.get(1).model().generatedName().contains("Persistence"));
    }

    @SneakyThrows
    @Test
    void appliesStepScopedAspectWhenTargetUsesNormalizedYamlToken() {
        ResolvedStep step = resolvedStep("ProcessFolderService");
        Map<String, Object> config = aspectConfig("org.pipelineframework.plugin.persistence.PersistenceService");
        config.put("targetSteps", List.of("ProcessFolder"));

        PipelineAspectModel stepScoped = new PipelineAspectModel(
            "persistence",
            AspectScope.STEPS,
            AspectPosition.AFTER_STEP,
            config
        );

        AspectExpansionProcessor processor = new AspectExpansionProcessor();
        List<ResolvedStep> expanded = processor.expandAspects(
            List.of(step),
            List.of(stepScoped)
        );

        assertEquals(2, expanded.size(), "Expected original step and one synthetic step");
        assertEquals(step.model().serviceName(), expanded.get(0).model().serviceName());
        assertTrue(expanded.get(1).model().generatedName().contains("Persistence"));
    }

    @SneakyThrows
    @Test
    void appliesStepScopedAspectWhenTargetUsesAuthoredStepTokenAgainstGeneratedServiceName() {
        ResolvedStep step = resolvedStepWithoutBinding("ProcessFinalizePaymentOutputService");
        Map<String, Object> config = aspectConfig("org.pipelineframework.plugin.persistence.PersistenceService");
        config.put("targetSteps", List.of("FinalizePaymentOutput"));

        PipelineAspectModel stepScoped = new PipelineAspectModel(
            "persistence",
            AspectScope.STEPS,
            AspectPosition.AFTER_STEP,
            config
        );

        AspectExpansionProcessor processor = new AspectExpansionProcessor();
        List<ResolvedStep> expanded = processor.expandAspects(
            List.of(step, resolvedStepWithoutBinding("ProcessCsvPaymentsInputService")),
            List.of(stepScoped)
        );

        assertEquals(3, expanded.size(), "Expected the targeted step plus one synthetic side effect");
        assertEquals(step.model().serviceName(), expanded.get(0).model().serviceName());
        assertTrue(expanded.get(1).model().generatedName().contains("Persistence"));
        assertEquals("ProcessCsvPaymentsInputService", expanded.get(2).model().serviceName());
    }

    @SneakyThrows
    @Test
    void assignsPluginRoleAndTargetsToSyntheticSteps() {
        ResolvedStep step = resolvedStep("ProcessFolderService");
        Map<String, Object> config = aspectConfig("org.pipelineframework.plugin.persistence.PersistenceService");

        PipelineAspectModel aspect = new PipelineAspectModel(
            "persistence",
            AspectScope.GLOBAL,
            AspectPosition.BEFORE_STEP,
            config
        );

        AspectExpansionProcessor processor = new AspectExpansionProcessor();
        List<ResolvedStep> expanded = processor.expandAspects(
            List.of(step),
            List.of(aspect)
        );

        PipelineStepModel synthetic = expanded.stream()
            .map(ResolvedStep::model)
            .filter(PipelineStepModel::sideEffect)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected a synthetic side-effect step"));
        assertEquals(DeploymentRole.PLUGIN_SERVER, synthetic.deploymentRole());
        assertEquals(Set.of(GenerationTarget.GRPC_SERVICE, GenerationTarget.CLIENT_STEP), synthetic.enabledTargets());
        assertEquals(step.model().servicePackage(), synthetic.servicePackage());
        assertEquals("PersistenceService", synthetic.serviceClassName().simpleName());
        assertEquals(StreamingShape.UNARY_UNARY, synthetic.streamingShape());
    }

    @SneakyThrows
    @Test
    void configuredTargetsMakeAspectsAroundProviderBackedStepsLoadableLocally() {
        ResolvedStep step = resolvedStepWithoutBinding("ProcessAnalyseInvoiceQueryClientStep");
        PipelineStepModel providerBacked = step.model().toBuilder()
            .enabledTargets(Set.of(GenerationTarget.QUERY_CLIENT_STEP))
            .build();
        Map<String, Object> config = aspectConfig("org.pipelineframework.plugin.persistence.PersistenceService");
        config.put("enabledTargets", List.of("LOCAL_CLIENT_STEP"));

        List<ResolvedStep> expanded = new AspectExpansionProcessor().expandAspects(
            List.of(new ResolvedStep(providerBacked, step.grpcBinding(), step.restBinding())),
            List.of(new PipelineAspectModel(
                "persistence", AspectScope.GLOBAL, AspectPosition.AFTER_STEP, config)));

        PipelineStepModel synthetic = expanded.stream()
            .map(ResolvedStep::model)
            .filter(PipelineStepModel::sideEffect)
            .findFirst()
            .orElseThrow();
        assertEquals(Set.of(GenerationTarget.LOCAL_CLIENT_STEP), synthetic.enabledTargets());
    }

    @SneakyThrows
    @Test
    void syntheticStepsReuseGrpcBinding() {
        ResolvedStep step = resolvedStep("ProcessFolderService");

        Map<String, Object> config = aspectConfig("org.pipelineframework.plugin.persistence.PersistenceService");
        PipelineAspectModel aspect = new PipelineAspectModel(
            "persistence",
            AspectScope.GLOBAL,
            AspectPosition.AFTER_STEP,
            config
        );

        AspectExpansionProcessor processor = new AspectExpansionProcessor();
        List<ResolvedStep> expanded = processor.expandAspects(
            List.of(step),
            List.of(aspect)
        );

        ResolvedStep synthetic = expanded.get(1);
        assertNull(synthetic.grpcBinding(), "Synthetic step defers gRPC binding resolution");
        assertNotEquals(step.model().serviceName(), synthetic.model().serviceName());
        assertNotEquals(step.model().generatedName(), synthetic.model().generatedName());
    }

    @SneakyThrows
    @Test
    void expandsBeforeAndAfterAroundOriginalStep() {
        ResolvedStep step = resolvedStepWithInputOutput(
            "ProcessFolderService",
            "CsvFolder",
            "CsvPaymentsInputFile");

        PipelineAspectModel beforeAspect = new PipelineAspectModel(
            "cache-invalidate",
            AspectScope.GLOBAL,
            AspectPosition.BEFORE_STEP,
            aspectConfig("org.pipelineframework.plugin.cache.CacheInvalidationService")
        );
        PipelineAspectModel afterAspect = new PipelineAspectModel(
            "persistence",
            AspectScope.GLOBAL,
            AspectPosition.AFTER_STEP,
            aspectConfig("org.pipelineframework.plugin.persistence.PersistenceService")
        );

        AspectExpansionProcessor processor = new AspectExpansionProcessor();
        List<ResolvedStep> expanded = processor.expandAspects(
            List.of(step),
            List.of(beforeAspect, afterAspect)
        );

        assertEquals(3, expanded.size());
        assertEquals("ObserveCacheInvalidateCsvFolderSideEffectService", expanded.get(0).model().serviceName());
        assertEquals(step.model().serviceName(), expanded.get(1).model().serviceName());
        assertEquals("ObservePersistenceCsvPaymentsInputFileSideEffectService", expanded.get(2).model().serviceName());
    }

    @SneakyThrows
    @Test
    void normalizesHyphenatedAspectNameForGeneratedStep() {
        ResolvedStep step = resolvedStepWithInputOutput(
            "ProcessFolderService",
            "CsvFolder",
            "CsvPaymentsInputFile");

        PipelineAspectModel beforeAspect = new PipelineAspectModel(
            "cache-invalidate",
            AspectScope.GLOBAL,
            AspectPosition.BEFORE_STEP,
            aspectConfig("org.pipelineframework.plugin.cache.CacheInvalidationService")
        );

        AspectExpansionProcessor processor = new AspectExpansionProcessor();
        List<ResolvedStep> expanded = processor.expandAspects(
            List.of(step),
            List.of(beforeAspect)
        );

        assertEquals("CacheInvalidateCsvFolderSideEffect", expanded.get(0).model().generatedName());
        assertEquals("ObserveCacheInvalidateCsvFolderSideEffectService", expanded.get(0).model().serviceName());
    }

    private static ResolvedStep resolvedStep(String serviceName) throws Exception {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName(serviceName)
            .servicePackage("org.pipelineframework.csv.service")
            .serviceClassName(ClassName.get("org.pipelineframework.csv.service", serviceName))
            .inputMapping(new TypeMapping(ClassName.get("org.pipelineframework.csv.common.domain", "CsvFolder"),
                ClassName.get("org.pipelineframework.csv.common.mapper", "CsvFolderMapper"), true))
            .outputMapping(new TypeMapping(ClassName.get("org.pipelineframework.csv.common.domain", "CsvPaymentsInputFile"),
                ClassName.get("org.pipelineframework.csv.common.mapper", "CsvPaymentsInputFileMapper"), true))
            .streamingShape(StreamingShape.UNARY_STREAMING)
            .enabledTargets(Set.of(GenerationTarget.GRPC_SERVICE, GenerationTarget.CLIENT_STEP))
            .executionMode(ExecutionMode.DEFAULT)
            .deploymentRole(DeploymentRole.PIPELINE_SERVER)
            .build();

        GrpcBindingResolver resolver = new GrpcBindingResolver();
        DescriptorProtos.FileDescriptorSet descriptors = loadDescriptorSet();
        GrpcBinding binding = resolver.resolve(model, descriptors);
        return new ResolvedStep(model, binding, null);
    }

    private static ResolvedStep resolvedStepWithoutBinding(String serviceName) {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName(serviceName)
            .servicePackage("org.pipelineframework.csv.service")
            .serviceClassName(ClassName.get("org.pipelineframework.csv.service", serviceName))
            .inputMapping(new TypeMapping(ClassName.get("org.pipelineframework.csv.common.domain", "CsvFolder"),
                ClassName.get("org.pipelineframework.csv.common.mapper", "CsvFolderMapper"), true))
            .outputMapping(new TypeMapping(ClassName.get("org.pipelineframework.csv.common.domain", "CsvPaymentsInputFile"),
                ClassName.get("org.pipelineframework.csv.common.mapper", "CsvPaymentsInputFileMapper"), true))
            .streamingShape(StreamingShape.UNARY_STREAMING)
            .enabledTargets(Set.of(GenerationTarget.GRPC_SERVICE, GenerationTarget.CLIENT_STEP))
            .executionMode(ExecutionMode.DEFAULT)
            .deploymentRole(DeploymentRole.PIPELINE_SERVER)
            .build();
        return new ResolvedStep(model, null, null);
    }

    private static ResolvedStep resolvedStepWithInputOutput(String serviceName, String inputType, String outputType)
        throws Exception {
        PipelineStepModel model = new PipelineStepModel.Builder()
            .serviceName(serviceName)
            .servicePackage("org.pipelineframework.csv.service")
            .serviceClassName(ClassName.get("org.pipelineframework.csv.service", serviceName))
            .inputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.csv.common.domain", inputType),
                ClassName.get("org.pipelineframework.csv.common.mapper", inputType + "Mapper"),
                true))
            .outputMapping(new TypeMapping(
                ClassName.get("org.pipelineframework.csv.common.domain", outputType),
                ClassName.get("org.pipelineframework.csv.common.mapper", outputType + "Mapper"),
                true))
            .streamingShape(StreamingShape.UNARY_STREAMING)
            .enabledTargets(Set.of(GenerationTarget.GRPC_SERVICE, GenerationTarget.CLIENT_STEP))
            .executionMode(ExecutionMode.DEFAULT)
            .deploymentRole(DeploymentRole.PIPELINE_SERVER)
            .build();

        GrpcBindingResolver resolver = new GrpcBindingResolver();
        DescriptorProtos.FileDescriptorSet descriptors = loadDescriptorSet();
        GrpcBinding binding = resolver.resolve(model, descriptors);
        return new ResolvedStep(model, binding, null);
    }

    private static Map<String, Object> aspectConfig(String pluginImplementationClass) {
        Map<String, Object> config = new HashMap<>();
        config.put("pluginImplementationClass", pluginImplementationClass);
        return config;
    }

    private static DescriptorProtos.FileDescriptorSet loadDescriptorSet() throws IOException {
        try (InputStream stream = AspectExpansionProcessorTest.class.getResourceAsStream("/descriptor_set.dsc")) {
            if (stream == null) {
                throw new IOException("Missing test descriptor set resource: /descriptor_set.dsc");
            }
            return DescriptorProtos.FileDescriptorSet.parseFrom(stream);
        }
    }
}
