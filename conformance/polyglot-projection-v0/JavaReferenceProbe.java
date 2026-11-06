import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;

/**
 * Independent Java research oracle over the same projection and fixed scenario answers.
 * This is a tiny CompletableFuture shell, not StepOneToOne or production runtime equivalence.
 * Its purpose is to expose contradictory attempts, stopping rules or arithmetic in native probes.
 */
final class JavaReferenceProbe {
    record Context(String executionId, String traceId) { }
    record Observation(String step, String executionId, String traceId) { }
    record Fault(String step, String code, int times) { }
    record Scenario(String id, int input, Optional<Fault> fault, boolean cancelBefore,
                    Optional<String> cancelAfter) { }
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Map<String, IntUnaryOperator> functions = Map.of(
        "a", value -> Math.addExact(value, 1),
        "b", value -> Math.multiplyExact(value, 2),
        "c", value -> Math.subtractExact(value, 3));

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("projection.json scenarios.json output.json");
        new JavaReferenceProbe().run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
    }

    private void run(Path projectionPath, Path scenariosPath, Path reportPath) throws Exception {
        var projection = json.readValue(Files.readAllBytes(projectionPath), PolyglotProjectionProbe.Projection.class);
        var scenarios = json.readTree(Files.readAllBytes(scenariosPath));
        if (!projection.profile().equals("tpf-polyglot-probe/0") || projection.schemaVersion() != 1
            || !projection.pipeline().transport().equals("LOCAL") || !projection.pipeline().platform().equals("COMPUTE")
            || !projection.pipeline().execution().equals("DIRECT") || !scenarios.path("schemaVersion").isInt()
            || scenarios.path("schemaVersion").intValue() != 1) {
            throw new IllegalArgumentException("JAVA_REFERENCE_REJECT:profile");
        }
        if (!projection.steps().stream().map(PolyglotProjectionProbe.Step::handler).toList().equals(List.of("a", "b", "c"))) {
            throw new IllegalArgumentException("JAVA_REFERENCE_REJECT:oracle only realizes a,b,c in that order");
        }
        var cases = new ArrayList<Map<String, Object>>();
        for (JsonNode row : scenarios.path("cases")) {
            Scenario scenario = readScenario(row);
            Map<String, Object> summary = execute(projection, scenario, new Context(scenario.id(), "trace-" + scenario.id()),
                Optional.empty(), new ArrayList<>()).join();
            equal(row.path("expected"), json.valueToTree(summary), scenario.id());
            var report = new LinkedHashMap<String, Object>();
            report.put("id", scenario.id());
            report.putAll(summary);
            cases.add(report);
        }
        // Both A invocations must arrive before either continues. Contexts are immutable values
        // passed to every async function, so overlap cannot read a shared last-invocation context.
        int contextCount = scenarios.path("contexts").size();
        if (contextCount < 2 || contextCount > 8) throw new IllegalArgumentException("JAVA_REFERENCE_REJECT:context count");
        var barrier = new CyclicBarrier(contextCount);
        var contexts = new ArrayList<CompletableFuture<Map<String, Object>>>();
        for (JsonNode row : scenarios.path("contexts")) {
            Context context = new Context(text(row, "executionId"), text(row, "traceId"));
            var observations = new ArrayList<Observation>();
            Scenario scenario = new Scenario(context.executionId(), int32(row.path("input")), Optional.empty(), false, Optional.empty());
            contexts.add(execute(projection, scenario, context, Optional.of(barrier), observations).thenApply(summary -> {
                equal(row.path("expected"), json.valueToTree(summary.get("result")), context.executionId());
                for (Observation observation : observations) {
                    if (!observation.executionId().equals(context.executionId()) || !observation.traceId().equals(context.traceId())) {
                        throw new IllegalStateException("JAVA_REFERENCE_MISMATCH:context isolation");
                    }
                }
                return Map.<String, Object>of("executionId", context.executionId(), "traceId", context.traceId(),
                    "result", summary.get("result"), "observations", List.copyOf(observations));
            }));
        }
        CompletableFuture.allOf(contexts.toArray(CompletableFuture[]::new)).join();
        var report = Map.of("language", "java", "profile", projection.profile(),
            "projectionSha256", digest(projectionPath), "scenariosSha256", digest(scenariosPath),
            "cases", cases, "contexts", contexts.stream().map(CompletableFuture::join).toList());
        Files.createDirectories(reportPath.toAbsolutePath().getParent());
        Files.writeString(reportPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        System.out.println("JAVA_REFERENCE_PASS:" + cases.size() + " scenarios," + contextCount + " overlapping contexts");
    }

    private CompletableFuture<Map<String, Object>> execute(PolyglotProjectionProbe.Projection projection,
        Scenario scenario, Context context, Optional<CyclicBarrier> barrier, List<Observation> observations) {
        return CompletableFuture.supplyAsync(() -> {
            var attempts = new LinkedHashMap<String, Integer>();
            projection.steps().forEach(step -> attempts.put(step.id(), 0));
            if (scenario.cancelBefore()) return summary("CANCELLED", attempts, Optional.empty(), Optional.empty());
            int value = scenario.input();
            for (var step : projection.steps()) {
                while (true) {
                    int attempt = attempts.compute(step.id(), (key, count) -> Math.addExact(count, 1));
                    observations.add(new Observation(step.id(), context.executionId(), context.traceId()));
                    if (step.id().equals("A") && attempt == 1) barrier.ifPresent(this::await);
                    Optional<Fault> activeFault = scenario.fault()
                        .filter(fault -> fault.step().equals(step.id()) && attempt <= fault.times());
                    if (activeFault.isPresent()) {
                        String code = activeFault.orElseThrow().code();
                        if (code.equals("TRANSIENT") && attempt <= step.retryLimit()) continue;
                        return summary("FAILED", attempts, Optional.empty(), Optional.of(code));
                    }
                    value = functions.get(step.handler()).applyAsInt(value);
                    break;
                }
                if (scenario.cancelAfter().filter(step.id()::equals).isPresent()) {
                    return summary("CANCELLED", attempts, Optional.empty(), Optional.empty());
                }
            }
            return summary("COMPLETED", attempts, Optional.of(value), Optional.empty());
        });
    }

    private Map<String, Object> summary(String outcome, Map<String, Integer> attempts,
        Optional<Integer> result, Optional<String> failure) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("outcome", outcome);
        summary.put("attempts", Map.copyOf(attempts));
        result.ifPresent(value -> summary.put("result", Map.of("value", value)));
        failure.ifPresent(code -> summary.put("failureCode", code));
        return Map.copyOf(summary);
    }

    private Scenario readScenario(JsonNode row) {
        Optional<Fault> fault = Optional.empty();
        if (row.has("fault")) {
            JsonNode raw = row.path("fault");
            String code = text(raw, "code");
            if (!List.of("TRANSIENT", "BUSINESS_REJECTION").contains(code) || !raw.path("times").isInt()
                || raw.path("times").intValue() < 1) throw new IllegalArgumentException("JAVA_REFERENCE_REJECT:fault");
            fault = Optional.of(new Fault(text(raw, "step"), code, raw.path("times").intValue()));
        }
        return new Scenario(text(row, "id"), int32(row.path("input")), fault,
            row.path("cancelBefore").asBoolean(false), Optional.ofNullable(row.get("cancelAfter")).map(JsonNode::textValue));
    }
    private static int int32(JsonNode value) {
        JsonNode number = value.path("value");
        if (!value.isObject() || value.size() != 1 || !number.isIntegralNumber() || !number.canConvertToInt()) {
            throw new IllegalArgumentException("JAVA_REFERENCE_REJECT:int32");
        }
        return number.intValue();
    }
    private static String text(JsonNode row, String field) {
        if (!row.path(field).isTextual() || row.path(field).textValue().isBlank()) throw new IllegalArgumentException("JAVA_REFERENCE_REJECT:" + field);
        return row.path(field).textValue();
    }
    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }
    private static void equal(JsonNode expected, JsonNode actual, String at) {
        if (!expected.equals(actual)) throw new IllegalStateException("JAVA_REFERENCE_MISMATCH:" + at + ":" + actual);
    }
    private static String digest(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
