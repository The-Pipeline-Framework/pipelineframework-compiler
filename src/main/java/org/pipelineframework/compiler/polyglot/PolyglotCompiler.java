package org.pipelineframework.compiler.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.pipelineframework.config.pipeline.PipelineYamlConfig;
import org.pipelineframework.config.pipeline.PipelineYamlConfigLoader;
import org.pipelineframework.config.pipeline.PipelineYamlConnectorBinding;
import org.pipelineframework.config.pipeline.PipelineYamlOperationSelection;
import org.pipelineframework.config.pipeline.PipelineYamlStep;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.config.template.PipelineTemplateDialect;
import org.pipelineframework.config.template.PipelineTemplateStep;
import org.pipelineframework.config.template.PipelineTemplateTypeModel;
import org.pipelineframework.processor.util.PipelineContractMetadataGenerator;

/**
 * Class-free canonical v3 polyglot compiler (ADR-0092).
 *
 * <p>Reads a canonical v3 {@code pipeline.yaml} using TPF's shared DSL loaders, validates step
 * boundaries and cardinalities ({@code ONE_TO_ONE}, {@code ONE_TO_MANY}, {@code MANY_TO_ONE},
 * {@code MANY_TO_MANY}),
 * emits a class-free schema v3 {@code META-INF/pipeline/pipeline-contract.json}, emits a
 * {@code META-INF/pipeline/polyglot-manifest.json} wiring descriptor for the Java execution engine
 * ({@code CanonicalFunctionHost*} and Tier-2 {@code PolyglotConnectorProvider}), and renders thin
 * typed target-language stubs ({@code types.ts}/{@code host.ts} or {@code types.py}/{@code host.py})
 * with exact {@code int64} and {@code decimal} precision.</p>
 */
public final class PolyglotCompiler {

    public static final String CONTRACT_RELATIVE_PATH = "META-INF/pipeline/pipeline-contract.json";
    public static final String MANIFEST_RELATIVE_PATH = "META-INF/pipeline/polyglot-manifest.json";
    public static final String POLYGLOT_CONNECTOR_PROVIDER = "polyglot.connector";

    private static final String FUNCTION_PATH_PREFIX = "/tpf/functions/";
    private static final String CONNECTOR_QUERY_PREFIX = "/tpf/connector/query/";
    private static final String CONNECTOR_COMMAND_PREFIX = "/tpf/connector/command/";
    private static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private PolyglotCompiler() {
    }

    /** Supported thin foreign host language targets. */
    public enum Target {
        TYPESCRIPT("typescript"),
        PYTHON("python");

        private final String id;

        Target(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Target parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("target must not be blank (expected typescript or python)");
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "typescript", "ts", "node" -> TYPESCRIPT;
                case "python", "py" -> PYTHON;
                default -> throw new IllegalArgumentException(
                    "Unsupported polyglot target '" + raw + "' (expected typescript or python)");
            };
        }
    }

    /** Execution host classification for a compiled polyglot step. */
    public enum StepHost {
        FOREIGN_FUNCTION("foreign-function"),
        POLYGLOT_CONNECTOR("polyglot-connector"),
        ENGINE("engine");

        private final String id;

        StepHost(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** Immutable summary returned after compiling a polyglot pipeline. */
    public record CompileResult(
        String pipelineId,
        String contractVersion,
        String catalogFingerprint,
        Target target,
        List<Path> generatedFiles
    ) {
        public CompileResult {
            Objects.requireNonNull(pipelineId, "pipelineId");
            Objects.requireNonNull(contractVersion, "contractVersion");
            Objects.requireNonNull(catalogFingerprint, "catalogFingerprint");
            Objects.requireNonNull(target, "target");
            generatedFiles = List.copyOf(Objects.requireNonNull(generatedFiles, "generatedFiles"));
        }
    }

    /** Internal validated wiring model shared by the manifest serializer and target renderers. */
    record CompiledPlan(
        String pipelineId,
        String contractVersion,
        String catalogFingerprint,
        String inputCanonicalTypeId,
        String outputCanonicalTypeId,
        PipelineTemplateTypeModel typeModel,
        Map<String, String> definitionFingerprints,
        List<CompiledStep> steps,
        List<CompiledConnectorOperation> polyglotQueries,
        List<CompiledConnectorOperation> polyglotCommands
    ) {
    }

    record CompiledStep(
        int stepIndex,
        String authoredName,
        String stepId,
        String handlerKey,
        String kind,
        String cardinality,
        StepHost host,
        Optional<String> endpointPath,
        String inputCanonicalTypeId,
        String inputDefinitionFingerprint,
        String outputCanonicalTypeId,
        String outputDefinitionFingerprint,
        Optional<String> connectorBinding,
        Optional<String> connectorProvider,
        Optional<String> connectorOperation,
        Optional<Integer> connectorOperationVersion
    ) {
    }

    record CompiledConnectorOperation(
        String operationId,
        String handlerKey,
        int majorVersion,
        String bindingName,
        String inputCanonicalTypeId,
        String outputCanonicalTypeId,
        boolean reconciliationSupported,
        Optional<String> receiptReferenceKind,
        String dispatchPath,
        Optional<String> reconcilePath
    ) {
    }

    /**
     * Compiles a canonical v3 {@code pipeline.yaml} into class-free contract metadata,
     * polyglot host wiring manifest, and thin typed target-language stubs.
     *
     * @param target target language ({@link Target#TYPESCRIPT} or {@link Target#PYTHON})
     * @param pipelineYaml path to {@code pipeline.yaml}
     * @param outputDirectory destination directory for generated artifacts
     * @return compilation summary with exact contract version, catalog fingerprint, and generated files
     * @throws IOException when reading {@code pipeline.yaml} or writing generated files fails
     */
    public static CompileResult compile(Target target, Path pipelineYaml, Path outputDirectory) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(pipelineYaml, "pipelineYaml must not be null");
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");

        PipelineTemplateConfig template = new PipelineTemplateConfigLoader().load(pipelineYaml);
        PipelineYamlConfig yamlConfig = new PipelineYamlConfigLoader().load(pipelineYaml);

        if (template.dialect() != PipelineTemplateDialect.V3) {
            throw new IllegalArgumentException(
                "Polyglot compilation requires canonical DSL version 3 (found " + template.dialect() + ")");
        }
        PipelineTemplateTypeModel typeModel = template.typeModel();
        if (typeModel == null || typeModel.definitions().isEmpty()) {
            throw new IllegalArgumentException("Polyglot compilation requires canonical v3 type definitions");
        }
        if (template.steps().isEmpty()) {
            throw new IllegalArgumentException("Polyglot compilation requires at least one pipeline step");
        }

        validateGraph(template, typeModel);

        String contractJson = new PipelineContractMetadataGenerator().renderPolyglotContract(template, yamlConfig);
        JsonObject contractTree = JsonParser.parseString(contractJson).getAsJsonObject();
        String contractVersion = contractTree.get("contractVersion").getAsString();
        String catalogFingerprint = contractTree.get("canonicalCatalogFingerprint").getAsString();
        Map<String, String> definitionFingerprints = extractDefinitionFingerprints(contractTree);

        CompiledPlan plan = buildPlan(
            template, yamlConfig, contractVersion, catalogFingerprint, definitionFingerprints);

        List<Path> written = new ArrayList<>();
        written.add(writeFile(outputDirectory.resolve(CONTRACT_RELATIVE_PATH), contractJson));
        written.add(writeFile(outputDirectory.resolve(MANIFEST_RELATIVE_PATH), renderManifestJson(target, plan)));

        Map<String, String> languageFiles = switch (target) {
            case TYPESCRIPT -> TypeScriptStubRenderer.render(plan);
            case PYTHON -> PythonStubRenderer.render(plan);
        };
        for (Map.Entry<String, String> entry : languageFiles.entrySet()) {
            written.add(writeFile(outputDirectory.resolve(entry.getKey()), entry.getValue()));
        }

        return new CompileResult(template.appName(), contractVersion, catalogFingerprint, target, written);
    }

    /** CLI entrypoint: {@code PolyglotCompiler <typescript|python> <pipeline.yaml> <output-directory>}. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("Usage: PolyglotCompiler <typescript|python> <pipeline.yaml> <output-directory>");
            System.exit(2);
            return;
        }
        Target target = Target.parse(args[0]);
        compile(target, Path.of(args[1]), Path.of(args[2]));
    }

    private static void validateGraph(PipelineTemplateConfig template, PipelineTemplateTypeModel typeModel) {
        Set<String> definedTypes = typeModel.definitions().keySet();
        String previousOutput = null;
        for (int i = 0; i < template.steps().size(); i++) {
            PipelineTemplateStep step = template.steps().get(i);
            if (step == null || step.name() == null || step.name().isBlank()) {
                throw new IllegalArgumentException("Step at index " + i + " must declare a non-blank name");
            }
            normalizeCardinality(step.name(), step.cardinality());
            requireDefinedType(definedTypes, step.inputTypeName(), "step '" + step.name() + "' inputType");
            requireDefinedType(definedTypes, step.outputTypeName(), "step '" + step.name() + "' outputType");
            for (String accepted : step.accepts()) {
                requireDefinedType(definedTypes, accepted, "step '" + step.name() + "' accepts");
            }
            if (previousOutput != null && step.accepts().isEmpty()
                && !previousOutput.equals(step.inputTypeName())) {
                throw new IllegalArgumentException(
                    "Type discontinuity at step '" + step.name() + "' (index " + i + "): expected input '"
                        + previousOutput + "' from previous step but declared '" + step.inputTypeName() + "'");
            }
            previousOutput = step.outputTypeName();
        }
        if (template.inputContract() != null && !template.inputContract().isBlank()) {
            requireDefinedType(definedTypes, template.inputContract(), "pipeline inputContract");
        }
        if (template.outputContract() != null && !template.outputContract().isBlank()) {
            requireDefinedType(definedTypes, template.outputContract(), "pipeline outputContract");
        }
    }

    private static CompiledPlan buildPlan(
        PipelineTemplateConfig template,
        PipelineYamlConfig yamlConfig,
        String contractVersion,
        String catalogFingerprint,
        Map<String, String> fingerprints
    ) {
        Map<String, PipelineYamlStep> yamlByToken = new LinkedHashMap<>();
        for (PipelineYamlStep yamlStep : yamlConfig.steps()) {
            if (yamlStep != null && yamlStep.name() != null) {
                yamlByToken.put(normalizeStepToken(yamlStep.name()), yamlStep);
            }
        }
        Map<String, PipelineYamlConnectorBinding> connectors = yamlConfig.connectors();

        Set<String> usedStepIds = new LinkedHashSet<>();
        Set<String> usedHandlerKeys = new LinkedHashSet<>();
        List<CompiledStep> compiledSteps = new ArrayList<>();
        Map<String, CompiledConnectorOperation> polyglotQueries = new LinkedHashMap<>();
        Map<String, CompiledConnectorOperation> polyglotCommands = new LinkedHashMap<>();

        for (int i = 0; i < template.steps().size(); i++) {
            PipelineTemplateStep step = template.steps().get(i);
            PipelineYamlStep yamlStep = yamlByToken.get(normalizeStepToken(step.name()));
            String kind = yamlStep == null ? "internal" : yamlStep.kind().trim().toLowerCase(Locale.ROOT);
            String cardinality = normalizeCardinality(step.name(), step.cardinality());
            String stepId = toKebabCase(step.name());
            if (!usedStepIds.add(stepId)) {
                throw new IllegalArgumentException(
                    "Duplicate normalized polyglot stepId '" + stepId + "' from step '" + step.name() + "'");
            }
            String handlerKey = toCamelCase(step.name());
            if (!usedHandlerKeys.add(handlerKey)) {
                throw new IllegalArgumentException(
                    "Duplicate normalized handler key '" + handlerKey + "' from step '" + step.name() + "'");
            }
            String inFp = requireFingerprint(fingerprints, step.inputTypeName());
            String outFp = requireFingerprint(fingerprints, step.outputTypeName());

            Optional<PipelineYamlOperationSelection> opSel = yamlStep == null
                ? Optional.empty()
                : yamlStep.operationSelection();

            if ("query".equals(kind) || "command".equals(kind)) {
                if (opSel.isPresent()) {
                    PipelineYamlOperationSelection sel = opSel.orElseThrow();
                    PipelineYamlConnectorBinding binding = connectors.get(sel.using());
                    if (binding == null) {
                        throw new IllegalArgumentException(
                            "Step '" + step.name() + "' references undeclared connector binding '" + sel.using() + "'");
                    }
                    if (POLYGLOT_CONNECTOR_PROVIDER.equals(binding.provider())) {
                        String opId = sel.operation();
                        String opHandlerKey = toCamelCase(opId.replace('.', ' '));
                        if ("query".equals(kind)) {
                            String dispatchPath = CONNECTOR_QUERY_PREFIX + opId;
                            polyglotQueries.putIfAbsent(opId, new CompiledConnectorOperation(
                                opId, opHandlerKey, sel.operationVersion(), binding.name(),
                                step.inputTypeName(), step.outputTypeName(), false, Optional.empty(),
                                dispatchPath, Optional.empty()));
                            compiledSteps.add(new CompiledStep(
                                i, step.name(), stepId, opHandlerKey, kind, cardinality,
                                StepHost.POLYGLOT_CONNECTOR, Optional.of(dispatchPath),
                                step.inputTypeName(), inFp, step.outputTypeName(), outFp,
                                Optional.of(binding.name()), Optional.of(binding.provider()),
                                Optional.of(opId), Optional.of(sel.operationVersion())));
                        } else {
                            String dispatchPath = CONNECTOR_COMMAND_PREFIX + opId + "/dispatch";
                            String reconcilePath = CONNECTOR_COMMAND_PREFIX + opId + "/reconcile";
                            Optional<String> receiptKind = extractReceiptReferenceKind(sel.policy(), binding.config());
                            boolean reconcileSupported = extractReconciliationSupported(sel.policy(), binding.config(), receiptKind);
                            polyglotCommands.putIfAbsent(opId, new CompiledConnectorOperation(
                                opId, opHandlerKey, sel.operationVersion(), binding.name(),
                                step.inputTypeName(), step.outputTypeName(), reconcileSupported, receiptKind,
                                dispatchPath, reconcileSupported ? Optional.of(reconcilePath) : Optional.empty()));
                            compiledSteps.add(new CompiledStep(
                                i, step.name(), stepId, opHandlerKey, kind, cardinality,
                                StepHost.POLYGLOT_CONNECTOR, Optional.of(dispatchPath),
                                step.inputTypeName(), inFp, step.outputTypeName(), outFp,
                                Optional.of(binding.name()), Optional.of(binding.provider()),
                                Optional.of(opId), Optional.of(sel.operationVersion())));
                        }
                    } else {
                        compiledSteps.add(new CompiledStep(
                            i, step.name(), stepId, handlerKey, kind, cardinality,
                            StepHost.ENGINE, Optional.empty(),
                            step.inputTypeName(), inFp, step.outputTypeName(), outFp,
                            Optional.of(binding.name()), Optional.of(binding.provider()),
                            Optional.of(sel.operation()), Optional.of(sel.operationVersion())));
                    }
                } else {
                    compiledSteps.add(new CompiledStep(
                        i, step.name(), stepId, handlerKey, kind, cardinality,
                        StepHost.ENGINE, Optional.empty(),
                        step.inputTypeName(), inFp, step.outputTypeName(), outFp,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
                }
            } else {
                String endpoint = switch (cardinality) {
                    case "ONE_TO_ONE" -> FUNCTION_PATH_PREFIX + stepId;
                    case "ONE_TO_MANY" -> FUNCTION_PATH_PREFIX + stepId + "/stream";
                    case "MANY_TO_ONE" -> FUNCTION_PATH_PREFIX + stepId + "/reduce";
                    case "MANY_TO_MANY" -> FUNCTION_PATH_PREFIX + stepId + "/duplex";
                    default -> throw new IllegalStateException("Unexpected cardinality: " + cardinality);
                };
                compiledSteps.add(new CompiledStep(
                    i, step.name(), stepId, handlerKey, kind, cardinality,
                    StepHost.FOREIGN_FUNCTION, Optional.of(endpoint),
                    step.inputTypeName(), inFp, step.outputTypeName(), outFp,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
            }
        }

        String inputContract = template.inputContract() != null && !template.inputContract().isBlank()
            ? template.inputContract()
            : template.steps().getFirst().inputTypeName();
        String outputContract = template.outputContract() != null && !template.outputContract().isBlank()
            ? template.outputContract()
            : template.steps().getLast().outputTypeName();

        return new CompiledPlan(
            template.appName(),
            contractVersion,
            catalogFingerprint,
            inputContract,
            outputContract,
            template.typeModel(),
            fingerprints,
            List.copyOf(compiledSteps),
            List.copyOf(polyglotQueries.values()),
            List.copyOf(polyglotCommands.values()));
    }

    private static String renderManifestJson(Target target, CompiledPlan plan) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("pipelineId", plan.pipelineId());
        root.put("contractVersion", plan.contractVersion());
        root.put("canonicalCatalogFingerprint", plan.catalogFingerprint());
        root.put("target", target.id());
        root.put("rootBoundary", Map.of(
            "inputCanonicalTypeId", plan.inputCanonicalTypeId(),
            "outputCanonicalTypeId", plan.outputCanonicalTypeId()));

        List<Map<String, Object>> stepsJson = new ArrayList<>();
        for (CompiledStep step : plan.steps()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepIndex", step.stepIndex());
            item.put("authoredName", step.authoredName());
            item.put("stepId", step.stepId());
            item.put("handlerKey", step.handlerKey());
            item.put("kind", step.kind());
            item.put("cardinality", step.cardinality());
            item.put("host", step.host().id());
            step.endpointPath().ifPresent(path -> item.put("endpointPath", path));
            item.put("inputCanonicalTypeId", step.inputCanonicalTypeId());
            item.put("inputDefinitionFingerprint", step.inputDefinitionFingerprint());
            item.put("outputCanonicalTypeId", step.outputCanonicalTypeId());
            item.put("outputDefinitionFingerprint", step.outputDefinitionFingerprint());
            step.connectorBinding().ifPresent(v -> item.put("connectorBinding", v));
            step.connectorProvider().ifPresent(v -> item.put("connectorProvider", v));
            step.connectorOperation().ifPresent(v -> item.put("connectorOperation", v));
            step.connectorOperationVersion().ifPresent(v -> item.put("connectorOperationVersion", v));
            stepsJson.add(item);
        }
        root.put("steps", stepsJson);

        Map<String, Object> connectorsJson = new LinkedHashMap<>();
        connectorsJson.put("queries", plan.polyglotQueries().stream().map(PolyglotCompiler::connectorOpMap).toList());
        connectorsJson.put("commands", plan.polyglotCommands().stream().map(PolyglotCompiler::connectorOpMap).toList());
        root.put("polyglotConnectors", connectorsJson);
        return PRETTY_GSON.toJson(root) + "\n";
    }

    private static Map<String, Object> connectorOpMap(CompiledConnectorOperation op) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("operationId", op.operationId());
        map.put("handlerKey", op.handlerKey());
        map.put("majorVersion", op.majorVersion());
        map.put("bindingName", op.bindingName());
        map.put("inputCanonicalTypeId", op.inputCanonicalTypeId());
        map.put("outputCanonicalTypeId", op.outputCanonicalTypeId());
        map.put("dispatchPath", op.dispatchPath());
        map.put("reconciliationSupported", op.reconciliationSupported());
        op.reconcilePath().ifPresent(v -> map.put("reconcilePath", v));
        op.receiptReferenceKind().ifPresent(v -> map.put("receiptReferenceKind", v));
        return map;
    }

    private static Optional<String> extractReceiptReferenceKind(
        Map<String, Object> policy, Map<String, Object> bindingConfig
    ) {
        for (Map<String, Object> source : List.of(policy, bindingConfig)) {
            if (source == null) {
                continue;
            }
            Object value = source.get("receiptReferenceKind");
            if (value instanceof String s && !s.isBlank()) {
                return Optional.of(s.trim());
            }
        }
        return Optional.empty();
    }

    private static boolean extractReconciliationSupported(
        Map<String, Object> policy, Map<String, Object> bindingConfig, Optional<String> receiptKind
    ) {
        for (Map<String, Object> source : List.of(policy, bindingConfig)) {
            if (source == null) {
                continue;
            }
            Object value = source.get("reconciliationSupported");
            if (value instanceof Boolean b) {
                return b;
            }
        }
        return receiptKind.isPresent();
    }

    private static Map<String, String> extractDefinitionFingerprints(JsonObject contractTree) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonObject types = contractTree.getAsJsonObject("canonicalTypes");
        for (String name : types.keySet()) {
            JsonObject entry = types.getAsJsonObject(name);
            result.put(name, entry.get("definitionFingerprint").getAsString());
        }
        return Map.copyOf(result);
    }

    private static String requireFingerprint(Map<String, String> fingerprints, String typeName) {
        String fp = fingerprints.get(typeName);
        if (fp == null || fp.isBlank()) {
            throw new IllegalStateException("Missing definitionFingerprint for canonical type '" + typeName + "'");
        }
        return fp;
    }

    private static void requireDefinedType(Set<String> definedTypes, String typeName, String role) {
        if (typeName == null || typeName.isBlank() || !definedTypes.contains(typeName)) {
            throw new IllegalArgumentException(
                "Unknown canonical type '" + typeName + "' referenced by " + role);
        }
    }

    private static String normalizeCardinality(String stepName, String raw) {
        String normalized = raw == null ? "ONE_TO_ONE" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ONE_TO_ONE", "SIDE_EFFECT" -> "ONE_TO_ONE";
            case "ONE_TO_MANY", "EXPANSION" -> "ONE_TO_MANY";
            case "MANY_TO_ONE", "REDUCTION" -> "MANY_TO_ONE";
            case "MANY_TO_MANY" -> "MANY_TO_MANY";
            default -> throw new IllegalArgumentException(
                "Step '" + stepName + "' declares unsupported polyglot cardinality '" + raw + "'");
        };
    }

    private static Path writeFile(Path destination, String content) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(destination, content, StandardCharsets.UTF_8);
        return destination;
    }

    static String toKebabCase(String raw) {
        List<String> words = splitWords(raw);
        if (words.isEmpty()) {
            throw new IllegalArgumentException("Identifier '" + raw + "' contains no alphanumeric characters");
        }
        return String.join("-", words);
    }

    static String toCamelCase(String raw) {
        List<String> words = splitWords(raw);
        if (words.isEmpty()) {
            throw new IllegalArgumentException("Identifier '" + raw + "' contains no alphanumeric characters");
        }
        StringBuilder out = new StringBuilder(words.getFirst());
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    static String toSnakeCase(String raw) {
        List<String> words = splitWords(raw);
        if (words.isEmpty()) {
            throw new IllegalArgumentException("Identifier '" + raw + "' contains no alphanumeric characters");
        }
        return String.join("_", words);
    }

    private static List<String> splitWords(String raw) {
        String spaced = raw.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String[] parts = spaced.split("[^A-Za-z0-9]+");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                words.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    private static String normalizeStepToken(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
