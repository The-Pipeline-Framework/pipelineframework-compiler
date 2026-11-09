/*
 * Test-only closed-profile projection experiment. No production compiler entrypoint is changed.
 * The released TPF loader owns YAML parsing, v3 normalization and canonical compatibility.
 */
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.pipelineframework.config.pipeline.PipelineYamlDocumentLoader;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.config.template.PipelineTemplateTypeReference;

/**
 * Produces a deliberately small native projection from the real, released v3 semantic model.
 *
 * <p>There are two independent gates. Shape admission rejects keys that the general loader may
 * legitimately ignore for its other consumers. It neither resolves types nor interprets flow.
 * The released loader then supplies all canonical declarations and normalized contracts. Only
 * that model is projected. A native bindings file realizes logical steps; it cannot reorder
 * steps or replace their contracts. retryLimit is a candidate research policy, not DSL syntax.
 */
public final class PolyglotProjectionProbe {
    private static final String PROFILE = "tpf-polyglot-probe/0";
    private static final String POLICY_PROFILE = "tpf-polyglot-unary-policy/1";
    private static final String CONTRACTS_VERSION = "26.11.1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern PIPELINE_ID = Pattern.compile("[a-z][a-z0-9-]*");
    // This probe deliberately does not invent a public cross-language identifier mangler.
    // Reject the union of Python/TypeScript keywords and names used by generated declarations.
    private static final Set<String> RESERVED = Set.of((
        "and as assert async await break case class const continue debugger declare def default del "
        + "delete do elif else enum except export extends False false finally for from function "
        + "global if implements import in infer instanceof interface is keyof lambda let match "
        + "module namespace new nonlocal not null None of or package pass private protected public "
        + "raise readonly return satisfies static super switch this throw True true try type typeof "
        + "unique var void while with yield number string boolean object unknown never any bigint "
        + "symbol undefined int Promise Readonly Awaitable Callable NotRequired TypedDict "
        + "ProbeContext HandlerRegistry LogicalTypes LogicalTypeId Int32").split(" "));
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    // These immutable records describe this research artifact only. They are not shared APIs,
    // wire contracts, an IDL lock, a Release manifest, or production compiler metadata.
    record Field(String name, String type, String presence, String nullability) { }
    record Type(String id, List<Field> fields) {
        Type { fields = List.copyOf(fields); }
    }
    record Step(String id, String inputType, String outputType, String handler, int retryLimit) { }
    record Pipeline(String id, String inputType, String outputType, String transport,
                    String platform, String execution) { }
    record Provenance(String dslSha256, String bindingsSha256, String generatorTreeSha256,
                      String contractsVersion, String dslJarSha256, String semanticModelJarSha256) { }
    record Projection(int schemaVersion, String profile, String kind, Pipeline pipeline,
                      List<String> requiredCapabilities, List<Type> types, List<Step> steps,
                      List<String> contextKeys, Provenance provenance) {
        Projection {
            requiredCapabilities = List.copyOf(requiredCapabilities);
            types = List.copyOf(types);
            steps = List.copyOf(steps);
            contextKeys = List.copyOf(contextKeys);
        }
    }
    record Binding(String step, String handler, String inputType, String outputType, int retryLimit) { }
    record Bindings(String profile, String pipelineId, List<Binding> handlers) {
        Bindings { handlers = List.copyOf(handlers); }
    }

    /** Versioned selection, not configurable flags that can silently weaken a profile. */
    record Policy(String failureMapping, String retryOwner, String resultMapping, String cancellation,
                  String context, String recovery, String legacyPolicy) {
        static Policy unary() {
            return new Policy("structured-failure/1", "LOCAL_SHELL", "explicit-value/1",
                "first-accepted-terminal/1", "explicit-snapshot/1", "REJECT", "REJECT");
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            if (args.length != 4) {
                throw reject("USAGE", "pipeline.yaml native-bindings.json output-directory generator-tree-sha256");
            }
            new PolyglotProjectionProbe().generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), args[3]);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            System.err.println(failure.getMessage());
            System.exit(2);
        }
    }

    void generate(Path dsl, Path bindingsFile, Path output, String generatorTreeHash) throws Exception {
        if (!generatorTreeHash.matches("[0-9a-f]{64}")) {
            throw reject("IDENTITY", "generator source tree digest must be SHA-256");
        }
        // Read the same bytes for admission and semantic loading; neither native consumer reads YAML.
        // The external runner stages immutable copies so an edited working file cannot race these reads.
        admitDocument(new PipelineYamlDocumentLoader().load(dsl));
        var warnings = new ArrayList<String>();
        var disabledOverrides = Map.<String, String>of();
        PipelineTemplateConfig model;
        try {
            model = new PipelineTemplateConfigLoader(disabledOverrides::get, disabledOverrides::get,
                warnings::add).load(dsl);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw reject("DSL_MODEL", failure.getMessage());
        }
        if (!warnings.isEmpty()) {
            throw reject("DSL_WARNING", String.join("; ", warnings));
        }
        Bindings bindings = readBindings(bindingsFile);
        Projection projection = project(model, bindings, new Provenance(
            digest(Files.readAllBytes(dsl)), digest(Files.readAllBytes(bindingsFile)), generatorTreeHash,
            CONTRACTS_VERSION, jarDigest(PipelineTemplateConfigLoader.class), jarDigest(PipelineTemplateConfig.class)));
        // Build everything before writing. A rejected input never leaves a partially generated artifact.
        com.fasterxml.jackson.databind.node.ObjectNode document = json.valueToTree(projection);
        // The v0 document shape remains unchanged. A v1 binding must explicitly admit
        // every selected policy; copying just the step order is insufficient.
        if (POLICY_PROFILE.equals(projection.profile())) document.set("policy", json.valueToTree(Policy.unary()));
        String encoded = json.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n";
        String typescript = new NativeModelEmitter().typescript(projection);
        String python = new NativeModelEmitter().python(projection);
        Files.createDirectories(output);
        Files.writeString(output.resolve("projection.json"), encoded, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("models.ts"), typescript, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("models.py"), python, StandardCharsets.UTF_8);
        System.out.println("PROJECTION_PASS:" + projection.pipeline().id());
    }

    /** Admission checks keys and container shapes only; all type/flow meaning comes from the model. */
    private void admitDocument(Object document) {
        Map<?, ?> root = yamlMap(document, "$root");
        exactKeys(root.keySet(), Set.of("version", "appName", "basePackage", "transport", "platform",
            "contract", "types", "steps"), "$root");
        // The general loader may default unknown transport/platform values. This closed profile
        // rejects them before loading; defaulting must never hide an unsupported requirement.
        if (!Integer.valueOf(3).equals(root.get("version")) || !"LOCAL".equals(root.get("transport"))
            || !"COMPUTE".equals(root.get("platform"))) {
            throw reject("UNSUPPORTED", "requires explicit integer version 3, LOCAL and COMPUTE");
        }
        exactKeys(yamlMap(root.get("contract"), "contract").keySet(), Set.of("input", "output"), "contract");
        Map<?, ?> types = yamlMap(root.get("types"), "types");
        for (var entry : types.entrySet()) {
            Map<?, ?> declaration = yamlMap(entry.getValue(), "type " + entry.getKey());
            exactKeys(declaration.keySet(), Set.of("fields"), "type " + entry.getKey());
            for (Object field : yamlList(declaration.get("fields"), "fields")) {
                // Both normal v3 spellings are admitted, without interpreting their marker syntax.
                if (field instanceof List<?> tuple) {
                    if (tuple.size() != 2) throw reject("UNSUPPORTED", "field tuple must have two entries");
                } else {
                    allowedKeys(yamlMap(field, "field").keySet(),
                        Set.of("name", "type", "presence", "nullability"), "field");
                }
            }
        }
        for (Object step : yamlList(root.get("steps"), "steps")) {
            exactKeys(yamlMap(step, "step").keySet(),
                Set.of("name", "cardinality", "input", "output"), "step");
        }
    }

    private Projection project(PipelineTemplateConfig model, Bindings bindings, Provenance provenance) {
        if (model.version() != 3 || !"LOCAL".equals(model.transport()) || !"COMPUTE".equals(model.platform().name())) {
            throw reject("UNSUPPORTED", "profile requires v3, LOCAL transport and COMPUTE platform");
        }
        if (!PIPELINE_ID.matcher(model.appName()).matches() || !model.appName().equals(bindings.pipelineId())) {
            throw reject("IDENTITY", "pipelineId must exactly equal the route-safe appName");
        }
        if (model.steps().isEmpty() || !model.pipelines().isEmpty() || !model.aspects().isEmpty()
            || !model.sources().isEmpty() || !model.publish().isEmpty()
            || !model.typeModel().representationMappings().isEmpty()) {
            throw reject("UNSUPPORTED", "only a nonempty pure linear unary flow is admitted");
        }
        List<Type> types = model.typeModel().definitions().values().stream()
            .sorted(Comparator.comparing(PipelineTemplateTypeDefinition::name))
            .map(this::projectType).toList();
        // Canonical type ids are authored logical names, never inferred Java/native class names.
        var usedBindings = new HashSet<String>();
        var usedHandlers = new HashSet<String>();
        var stepNames = new HashSet<String>();
        var steps = new ArrayList<Step>();
        String preceding = required(model.inputContract(), "pipeline input contract");
        for (var step : model.steps()) {
            identifier(step.name(), "step");
            if (!stepNames.add(step.name())) throw reject("AMBIGUOUS_STEP", step.name());
            if (!"ONE_TO_ONE".equals(step.cardinality())) {
                throw reject("UNSUPPORTED", "step " + step.name() + " requires ONE_TO_ONE");
            }
            // General v3 admits non-adjacent/branch-compatible inputs. This closed *linear* profile
            // requires the immediate previous output, using the canonical model's assignability.
            if (!model.typeModel().isAssignable(preceding, step.inputTypeName())) {
                throw reject("INCOMPATIBLE_LINK", preceding + " -> " + step.inputTypeName());
            }
            List<Binding> matches = bindings.handlers().stream().filter(b -> b.step().equals(step.name())).toList();
            if (matches.isEmpty()) throw reject("MISSING_HANDLER", step.name());
            if (matches.size() != 1) throw reject("AMBIGUOUS_HANDLER", step.name());
            Binding binding = matches.getFirst();
            if (!binding.inputType().equals(step.inputTypeName()) || !binding.outputType().equals(step.outputTypeName())) {
                throw reject("HANDLER_TYPE", "handler " + binding.handler() + " disagrees with logical step " + step.name());
            }
            if (!usedHandlers.add(binding.handler())) throw reject("AMBIGUOUS_HANDLER", binding.handler());
            usedBindings.add(binding.step());
            steps.add(new Step(step.name(), step.inputTypeName(), step.outputTypeName(), binding.handler(), binding.retryLimit()));
            preceding = step.outputTypeName();
        }
        if (bindings.handlers().stream().anyMatch(b -> !usedBindings.contains(b.step()))) {
            throw reject("EXTRA_HANDLER", "binding names an absent logical step");
        }
        String output = required(model.outputContract(), "pipeline output contract");
        if (!model.typeModel().isAssignable(preceding, output)) throw reject("INCOMPATIBLE_LINK", "pipeline output");
        if (POLICY_PROFILE.equals(bindings.profile())) {
            // These live adapters qualify a closed three-step, non-null record path.
            // PresenceProbe declarations remain useful type-generation evidence but do
            // not imply a nullable whole-result or arbitrary payload codec is executable.
            Type inputType = types.stream().filter(type -> type.id().equals(model.inputContract()))
                .findFirst().orElseThrow(() -> reject("UNSUPPORTED_TYPE", "missing executable record"));
            if (steps.size() != 3 || !inputType.fields().equals(List.of(new Field("value", "int32", "REQUIRED", "NON_NULL")))
                || !output.equals(inputType.id()) || steps.stream().anyMatch(step ->
                    !step.inputType().equals(inputType.id()) || !step.outputType().equals(inputType.id()))) {
                throw reject("UNSUPPORTED_POLICY", "unary policy binding requires three steps over one non-null value:int32 record");
            }
        }
        return new Projection(1, bindings.profile(), "compiler-projection-probe", new Pipeline(
            model.appName(), model.inputContract(), output, model.transport(), model.platform().name(), "DIRECT"),
            List.of("pure", "linear", "unary", "local", "retry", "cancellation", "context"), types, steps,
            List.of("executionId", "traceId"), provenance);
    }

    private Type projectType(PipelineTemplateTypeDefinition definition) {
        identifier(definition.name(), "type");
        if (!(definition instanceof PipelineTemplateTypeDefinition.RecordType record)) {
            throw reject("UNSUPPORTED_TYPE", definition.name() + ": only records of int32 are admitted");
        }
        List<Field> fields = record.fields().stream().map(field -> {
            identifier(field.name(), "field");
            if (!(field.type() instanceof PipelineTemplateTypeReference.Scalar scalar)
                || !"int32".equals(scalar.name()) || field.repeated()) {
                throw reject("UNSUPPORTED_TYPE", record.name() + "." + field.name() + ": only scalar int32 is admitted");
            }
            return new Field(field.name(), field.type().name(), field.presence().name(), field.nullability().name());
        }).toList();
        return new Type(record.name(), fields);
    }

    private Bindings readBindings(Path file) throws IOException {
        JsonNode root;
        try {
            root = json.readTree(Files.readAllBytes(file));
        } catch (IOException failure) {
            throw reject("BINDINGS_FORMAT", failure.getMessage());
        }
        exactJsonKeys(root, Set.of("schemaVersion", "profile", "pipelineId", "handlers"), "bindings");
        if (exactInteger(root.path("schemaVersion"), "BINDING_PROFILE") != 1
            || !Set.of(PROFILE, POLICY_PROFILE).contains(root.path("profile").asText())) {
            throw reject("BINDING_PROFILE", "requires exact schemaVersion 1 and known profile");
        }
        String pipelineId = jsonString(root, "pipelineId");
        if (!root.path("handlers").isArray()) throw reject("BINDINGS", "handlers must be an array");
        var handlers = new ArrayList<Binding>();
        for (JsonNode handler : root.path("handlers")) {
            exactJsonKeys(handler, Set.of("step", "handler", "inputType", "outputType", "retryLimit"), "handler");
            String symbol = jsonString(handler, "handler");
            identifier(symbol, "handler");
            int retry = exactInteger(handler.path("retryLimit"), "UNSUPPORTED_POLICY");
            if (retry < 0 || retry > 2) {
                throw reject("UNSUPPORTED_POLICY", "candidate retryLimit must have exact integer value 0..2");
            }
            handlers.add(new Binding(jsonString(handler, "step"), symbol, jsonString(handler, "inputType"),
                jsonString(handler, "outputType"), retry));
        }
        return new Bindings(root.path("profile").textValue(), pipelineId, handlers);
    }

    private static Map<?, ?> yamlMap(Object value, String at) {
        if (value instanceof Map<?, ?> map) return map;
        throw reject("SHAPE", at + " must be a map");
    }
    private static List<?> yamlList(Object value, String at) {
        if (value instanceof List<?> list) return list;
        throw reject("SHAPE", at + " must be a list");
    }
    private static void allowedKeys(Set<?> actual, Set<String> allowed, String at) {
        var extra = new TreeSet<String>();
        for (Object key : actual) if (!(key instanceof String text) || !allowed.contains(text)) extra.add(String.valueOf(key));
        if (!extra.isEmpty()) throw reject("UNSUPPORTED", at + " keys " + extra);
    }
    private static void exactKeys(Set<?> actual, Set<String> expected, String at) {
        allowedKeys(actual, expected, at);
        if (!actual.containsAll(expected)) throw reject("SHAPE", at + " missing required keys");
    }
    private static void exactJsonKeys(JsonNode node, Set<String> expected, String at) {
        if (!node.isObject()) throw reject("BINDINGS", at + " must be an object");
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        exactKeys(actual, expected, at);
    }
    private static String jsonString(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).textValue().isBlank()) throw reject("BINDINGS", key + " must be nonblank text");
        return node.path(key).textValue();
    }
    /** Profile configuration numbers compare by exact mathematical value, as in contracts55. */
    private static int exactInteger(JsonNode node, String code) {
        if (!node.isNumber()) throw reject(code, "configuration number required");
        try {
            return node.decimalValue().intValueExact();
        } catch (ArithmeticException failure) {
            throw reject(code, "configuration number must have an exact bounded integer value");
        }
    }
    private static String required(String value, String at) {
        return Optional.ofNullable(value).filter(text -> !text.isBlank()).orElseThrow(() -> reject("SHAPE", at + " is required"));
    }
    private static void identifier(String name, String at) {
        if (!IDENTIFIER.matcher(name).matches() || RESERVED.contains(name)) throw reject("IDENTIFIER", at + " " + name);
    }
    private static IllegalArgumentException reject(String code, String message) {
        return new IllegalArgumentException("PROJECTION_REJECT:" + code + ":" + message);
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String jarDigest(Class<?> loadedClass) throws Exception {
        Path jar = Path.of(loadedClass.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(jar)) throw reject("IDENTITY", "released API must be loaded from an immutable jar");
        return digest(Files.readAllBytes(jar));
    }
}
