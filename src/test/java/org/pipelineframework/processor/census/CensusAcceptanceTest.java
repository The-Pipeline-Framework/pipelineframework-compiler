package org.pipelineframework.processor.census;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.squareup.javapoet.ClassName;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.CardinalitySemantics;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.processor.*;
import org.pipelineframework.processor.block.BlockDefinitionImporter;
import org.pipelineframework.processor.composition.*;
import org.pipelineframework.processor.ir.*;
import org.pipelineframework.processor.mapping.PipelineRuntimeMapping;
import org.pipelineframework.processor.parser.*;

/** Independent logical census oracles; full compiler-host integration is tested separately. */
class CensusAcceptanceTest {
    @TempDir Path directory;

    @Test void countsAuthoredReachableLocationsIncludingRecursionAndImportedPackages() throws Exception {
        var ctx = fixture("private");
        // Root + reused child + recursive child + two definitions from one adopted package.
        assertEquals(5, ctx.getResolvedPipelineDefinitionGraph().orElseThrow().definitions().size());
        assertEquals(3, ctx.getImportedPipelineDefinitions().size(), "Unused dependency is actually present");
        assertEquals(8, ctx.getPipelineTemplateConfig().typeModel().definitions().size());
        var summary = new BuildCensusProjector().project(ctx).orElseThrow();
        assertEquals(Map.of("pipelines", CountBand.TWO_TO_FIVE, "steps", CountBand.ELEVEN_TO_TWENTY_FIVE,
                "types", CountBand.SIX_TO_TEN, "blocks", CountBand.ONE), summary.sizes());
        // Five root invocations and one self-invocation; the twice-called child's internals count once.
        // Reduction is present at the child's collect operation and at its two MANY_TO_ONE invocation locations.
        assertEquals(Map.of("nested_invocation", CountBand.SIX_TO_TEN, "one_to_many", CountBand.ONE,
                "many_to_one", CountBand.TWO_TO_FIVE, "query", CountBand.ONE, "command", CountBand.ONE,
                "await", CountBand.ONE), summary.featureCounts());
        assertEquals(1, ctx.getParsedPipelineDefinitionCatalog().localDefinitions().get("privateChild").stream()
                .filter(step -> step.deferredCompletion().isPresent()).count(), "Await belongs to its operation");
    }

    @Test void runtimeLayoutsAndGeneratedRoleCopiesCannotChangeLogicalCounts() throws Exception {
        var ctx = fixture("private");
        var expected = new BuildCensusProjector().project(ctx).orElseThrow();
        var service = ClassName.get("privatebusiness", "SyntheticObserver");
        var model = new PipelineStepModel.Builder().serviceName("generated-observer")
                .servicePackage("privatebusiness").serviceClassName(service)
                .inputMapping(TypeMapping.withoutMapper(service)).outputMapping(TypeMapping.withoutMapper(service))
                .streamingShape(StreamingShape.UNARY_STREAMING).executionMode(ExecutionMode.DEFAULT)
                .deploymentRole(DeploymentRole.PLUGIN_CLIENT).sideEffect(true).build();
        for (var layout : PipelineRuntimeMapping.Layout.values()) {
            ctx.setRuntimeMapping(new PipelineRuntimeMapping(layout, PipelineRuntimeMapping.Validation.AUTO,
                    PipelineRuntimeMapping.Defaults.defaultValues(), Map.of(), Map.of(), Map.of(), Map.of()));
            List<PipelineStepModel> generated = new ArrayList<>();
            for (int i = 0; i < 40; i++)
                for (var role : DeploymentRole.values()) generated.add(model.withDeploymentRole(role));
            ctx.setStepModels(generated);
            var actual = new BuildCensusProjector().project(ctx).orElseThrow();
            assertEquals(expected.sizes(), actual.sizes(), layout.name());
            assertEquals(expected.featureCounts(), actual.featureCounts(), layout.name());
            assertEquals(BuildCensusSummary.Layout.valueOf(layout.name()), actual.runtimeLayout());
            ctx.setStepModels(List.of());
            assertEquals(actual, new BuildCensusProjector().project(ctx).orElseThrow(), "Module filtering changes no facts");
        }
    }

    @Test void renamedApplicationSymbolsAndPrivateSourceValuesNeverEnterCandidateJson() throws Exception {
        var first = new BuildCensusProjector().project(fixture("private")).orElseThrow();
        var renamed = new BuildCensusProjector().project(fixture("renamed")).orElseThrow();
        assertEquals(first, renamed);
        String json = new CensusEvent(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 11, 7),
                "26.11.2-SNAPSHOT", "26.11.2-SNAPSHOT", 25, first).json();
        for (String forbidden : List.of("private", "renamed", "business", "secret-token", "partner.invalid",
                "customer_lookup", "SecretSchema", "schema-secret", "private.command", "provider-secret",
                "private.coordinates", "sha256:")) {
            assertFalse(json.contains(forbidden), forbidden);
        }
        assertFalse(json.contains(directory.toString()));
        assertFalse(json.contains("digest"));
        assertTrue(json.contains("26.11.2-dev"));
    }

    @Test void knownJenkinsBuildNeedsRetainedStateEvenForInspection() {
        Properties properties = new Properties();
        properties.setProperty("user.home", directory.toString()); properties.setProperty("os.name", "Mac OS X");
        var options = Map.of("tpf.telemetry.mode", "preview", "tpf.telemetry.preview", directory.resolve("preview.json").toString());
        var environment = new HashMap<>(Map.of("JENKINS_URL", "https://jenkins.invalid", "BUILD_ID", "123"));
        assertEquals(CensusConfiguration.Mode.OFF, CensusConfiguration.resolve(options, environment, properties).mode());
        environment.put("TPF_TELEMETRY_STATE_PERSISTENT", "true");
        assertEquals(CensusConfiguration.Mode.PREVIEW, CensusConfiguration.resolve(options, environment, properties).mode());
    }

    @Test void applicationResourceCannotOverrideOwningCompilerArtifactVersion() throws Exception {
        String resource = "/META-INF/tpf/compiler-version.properties";
        String expected = CensusEvent.packagedVersion(CensusEvent.class, resource);
        var artifact = CensusEvent.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new java.net.URL[] { artifact }, ClassLoader.getPlatformClassLoader()) {
            @Override public java.io.InputStream getResourceAsStream(String name) {
                if (name.equals("META-INF/tpf/compiler-version.properties"))
                    return new java.io.ByteArrayInputStream("version=1234.56.78\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return super.getResourceAsStream(name);
            }
        }) {
            Class<?> owner = loader.loadClass(CensusEvent.class.getName());
            assertEquals("version=1234.56.78\n", new String(owner.getResourceAsStream(resource).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8), "The competing application resource is reachable");
            assertEquals(expected, CensusEvent.packagedVersion(owner, resource));
            assertNotEquals("1234.56.78", CensusEvent.packagedVersion(owner, resource));
        }
    }

    private PipelineCompilationContext fixture(String prefix) throws Exception {
        Path root = Files.createDirectories(directory.resolve(prefix));
        Path used = blockPackage(root, prefix + ".adopted", "adopted", true);
        Path unused = blockPackage(root, prefix + ".unused", "unused", false);
        String application = """
                version: 3
                appName: private-secret-token
                basePackage: privatebusiness
                transport: LOCAL
                platform: COMPUTE
                contract: { input: Value, output: Value }
                types:
                  Pending: { fields: [[id, string]] }
                  SecretSchema: { fields: [[schema-secret, string]] }
                  Fourth: { fields: [[id, string]] }
                  Fifth: { fields: [[id, string]] }
                queries:
                  customer_lookup:
                    connector: jpa
                    input: privatebusiness.Value
                    output: privatebusiness.Value
                    jpa: { entity: privatebusiness.SecretEntity, where: { id: input.id } }
                pipelines:
                  child:
                    input: Value
                    output: Value
                    steps:
                      - { name: load, kind: query, query: customer_lookup, cardinality: ONE_TO_ONE, input: Value, output: Value, java: { input: privatebusiness.Value, output: privatebusiness.Value }, capture: { keyFields: [id] } }
                      - { name: expand, service: privatebusiness.Expand, cardinality: ONE_TO_MANY, input: Value, output: Value }
                      - { name: collect, service: privatebusiness.Collect, cardinality: MANY_TO_ONE, input: Value, output: Value }
                      - { name: effect, kind: command, command: private.command, commandIdGenerator: privatebusiness.IdGenerator, duplicatePolicy: FAIL, config: { token: secret-token, provider: provider-secret }, input: Value, output: Value, cardinality: ONE_TO_ONE, java: { input: privatebusiness.Value, output: privatebusiness.Value } }
                      - name: wait
                        service: privatebusiness.Wait
                        cardinality: ONE_TO_ONE
                        input: Value
                        output: Value
                        await:
                          operationOutput: { type: Pending, java: privatebusiness.Pending }
                          timeout: PT10M
                          correlation: { strategy: interactionId }
                          transport: { type: webhook, request: { url: 'https://partner.invalid/secret-token' } }
                  recursive:
                    input: Value
                    output: Value
                    steps:
                      - { name: tick, service: privatebusiness.Tick, cardinality: ONE_TO_ONE, input: Value, output: Value }
                      - { name: repeat, pipeline: recursive, cardinality: ONE_TO_ONE, input: Value, output: Value }
                  unusedLocal:
                    input: Value
                    output: Value
                    steps:
                """ + repeatedSteps(26, 6) + """
                steps:
                  - { name: first, pipeline: child, cardinality: MANY_TO_ONE, input: Value, output: Value }
                  - { name: second, pipeline: child, cardinality: MANY_TO_ONE, input: Value, output: Value }
                  - { name: recurse, pipeline: recursive, cardinality: ONE_TO_ONE, input: Value, output: Value }
                  - { name: importA, pipeline: piece, cardinality: ONE_TO_ONE, input: Value, output: Value }
                  - { name: importB, pipeline: otherPiece, cardinality: ONE_TO_ONE, input: Value, output: Value }
                """;
        Path source = root.resolve("pipeline.yaml");
        application = application.replace("input: Value, output: Value }",
                "input: Value, output: Value, java: { input: privatebusiness.Value, output: privatebusiness.Value } }");
        Files.writeString(source, application.replace("private", prefix).replace("child", prefix + "Child")
                .replace("recursive", prefix + "Recursive").replace("name: ", "name: " + prefix + "-"));
        try (var loader = new URLClassLoader(new java.net.URL[] { used.toUri().toURL(), unused.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
             var imported = new BlockDefinitionImporter(loader).importInto(source)) {
            var config = new PipelineTemplateConfigLoader().load(imported.configPath());
            List<String> errors = new ArrayList<>();
            var catalog = new StepDefinitionParser((kind, message) -> {
                if (kind == Diagnostic.Kind.ERROR) errors.add(message);
            }).parseDefinitionCatalog(imported.configPath());
            assertTrue(errors.isEmpty(), errors.toString());
            assertEquals(5, catalog.rootSteps().size());
            assertEquals(5, catalog.localDefinitions().get(prefix + "Child").size());
            var ctx = new PipelineCompilationContext(mock(ProcessingEnvironment.class), Jsr269SourceInventory.empty(),
                    new PipelineCompilerOptions(Map.of()), (severity, message) -> { });
            ctx.setOriginalPipelineConfig(Optional.of(source)); ctx.setOrchestratorGenerated(true);
            ctx.setPipelineTemplateConfig(config); ctx.setParsedPipelineDefinitionCatalog(catalog);
            ctx.setImportedPipelineDefinitions(imported.definitions()); ctx.setStepModels(List.of());
            ctx.setTransportMode(PipelineTransport.LOCAL);
            Map<PipelineReference, PipelineDefinition> definitions = new LinkedHashMap<>();
            var rootReference = new PipelineReference("$root");
            definitions.put(rootReference, definition(rootReference, catalog.rootSteps()));
            catalog.localDefinitions().forEach((id, steps) -> {
                var reference = new PipelineReference(id); definitions.put(reference, definition(reference, steps));
            });
            ctx.setResolvedPipelineDefinitionGraph(new PipelineDefinitionLinker(reference -> Optional.ofNullable(definitions.get(reference)))
                    .link(definitions.get(rootReference)));
            return ctx;
        }
    }

    private PipelineDefinition definition(PipelineReference reference, List<StepDefinition> authored) {
        return new PipelineDefinition(reference, "Value", "Value", authored.stream().map(step -> {
            if (step.pipelineReference().isPresent()) return PipelineDefinitionStep.pipeline(step.name(), "Value", "Value",
                    new PipelineReference(step.pipelineReference().orElseThrow()));
            CardinalitySemantics cardinality = switch (step.streamingShapeHint()) {
                case UNARY_UNARY -> CardinalitySemantics.ONE_TO_ONE;
                case UNARY_STREAMING -> CardinalitySemantics.ONE_TO_MANY;
                case STREAMING_UNARY -> CardinalitySemantics.MANY_TO_ONE;
                case STREAMING_STREAMING -> CardinalitySemantics.MANY_TO_MANY;
            };
            return PipelineDefinitionStep.direct(step.name(), "Value", "Value", cardinality);
        }).toList());
    }

    private Path blockPackage(Path root, String namespace, String artifact, boolean adopted) throws Exception {
        Path classes = root.resolve(artifact); Path metadata = Files.createDirectories(classes.resolve("META-INF/pipeline"));
        Files.writeString(metadata.resolve("blocks.json"), """
                {"schemaVersion":1,"namespace":"%s","artifact":{"groupId":"private.coordinates","artifactId":"%s","version":"1.2.3-secret"},
                 "definitions":[%s]}
                """.formatted(namespace, artifact, adopted
                        ? "{\"name\":\"piece\",\"resource\":\"META-INF/pipeline/definition.yaml\"},{\"name\":\"otherPiece\",\"resource\":\"META-INF/pipeline/definition.yaml\"}"
                        : "{\"name\":\"unusedPiece\",\"resource\":\"META-INF/pipeline/definition.yaml\"}"));
        String yaml = "version: 3\ntypes:\n" + (adopted
                        ? "  Value: { java: privatebusiness.Value, fields: [[id, string]] }\n  ContributedOne: { fields: [[id, string]] }\n  ContributedTwo: { fields: [[id, string]] }\n"
                        : "  UnusedContributed: { fields: [[id, string]] }\n") + "pipelines:\n";
        for (String name : adopted ? List.of("piece", "otherPiece") : List.of("unusedPiece")) {
            String definition = "  " + name + ":\n    input: Value\n    output: Value\n    steps:\n" + repeatedSteps(adopted ? 1 : 26, 6);
            yaml += adopted ? definition : definition.replace("Value", "UnusedContributed");
        }
        Files.writeString(metadata.resolve("definition.yaml"), yaml.replace("private", root.getFileName().toString()));
        return classes;
    }

    private String repeatedSteps(int number, int indentation) {
        StringBuilder yaml = new StringBuilder();
        for (int i = 0; i < number; i++) yaml.append(" ".repeat(indentation)).append("- { name: operation")
                .append(i).append(", service: privatebusiness.Service, cardinality: ONE_TO_ONE, input: Value, output: Value }\n");
        return yaml.toString();
    }
}
