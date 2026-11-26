package org.pipelineframework.compiler.polyglot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.orchestrator.TransitionWorkerSignature;

import static org.junit.jupiter.api.Assertions.*;

class PolyglotCompilerTest {

    private static final String SHARED_SECRET = "tpf-test-secret-0123456789";

    private static final String FULL_V3_PIPELINE_YAML = """
        version: 3
        appName: InvoiceEngine
        basePackage: com.example.invoice
        transport: LOCAL
        platform: COMPUTE
        types:
          CurrencyCode:
            wraps: string
            allowedValues: [USD, EUR]
          MoneyAmount:
            alias: decimal
          ApprovedTag:
            fields:
              - name: code
                type: string
          RejectedTag:
            fields:
              - name: reason
                type: string
          DecisionOutcome:
            variants:
              approved: ApprovedTag
              rejected: RejectedTag
          InvoiceBatch:
            fields:
              - name: batchSeq
                type: int64
              - name: grossAmount
                type: decimal
              - name: currency
                type: CurrencyCode
              - name: memo
                type: string
                presence: optional
              - name: overrideNote
                type: string
                nullability: nullable
              - name: tags
                repeated: string
              - name: feeAmount
                type: MoneyAmount
              - name: decision
                type: DecisionOutcome
          InvoiceLine:
            fields:
              - name: lineSeq
                type: int64
              - name: amount
                type: decimal
          EnrichedLine:
            fields:
              - name: lineSeq
                type: int64
              - name: amount
                type: decimal
              - name: rateSource
                type: string
          BatchSummary:
            fields:
              - name: totalSeq
                type: int64
              - name: totalAmount
                type: decimal
              - name: lineCount
                type: int32
          PaymentReceipt:
            fields:
              - name: receiptId
                type: string
              - name: settledAmount
                type: decimal
        connectors:
          fx-api:
            provider: http.client
            version: 1
          ledger-host:
            provider: polyglot.connector
            version: 1
            config:
              receiptReferenceKind: ledger.tx
              reconciliationSupported: true
        steps:
          - name: Validate Batch
            cardinality: ONE_TO_ONE
            input: InvoiceBatch
            output: InvoiceBatch
          - name: Split Lines
            cardinality: ONE_TO_MANY
            input: InvoiceBatch
            output: InvoiceLine
          - name: Lookup Rate
            kind: query
            using: ledger-host
            operation: ledger.rate
            input: InvoiceLine
            output: EnrichedLine
          - name: Fetch Tax
            kind: query
            using: fx-api
            operation: http.get
            input: EnrichedLine
            output: EnrichedLine
          - name: Sum Lines
            cardinality: MANY_TO_ONE
            input: EnrichedLine
            output: BatchSummary
          - name: Post Settlement
            kind: command
            using: ledger-host
            operation: ledger.settle
            input: BatchSummary
            output: PaymentReceipt
        """;

    @Test
    void emitsIdenticalClassFreeSchemaV3ContractAndManifestAcrossTypeScriptAndPython(@TempDir Path temp) throws IOException {
        Path yaml = temp.resolve("pipeline.yaml");
        Files.writeString(yaml, FULL_V3_PIPELINE_YAML);

        Path tsOut = temp.resolve("out-ts");
        Path pyOut = temp.resolve("out-py");

        PolyglotCompiler.CompileResult tsResult = PolyglotCompiler.compile(PolyglotCompiler.Target.TYPESCRIPT, yaml, tsOut);
        PolyglotCompiler.CompileResult pyResult = PolyglotCompiler.compile(PolyglotCompiler.Target.PYTHON, yaml, pyOut);

        assertEquals(tsResult.contractVersion(), pyResult.contractVersion());
        assertEquals(tsResult.catalogFingerprint(), pyResult.catalogFingerprint());
        assertTrue(tsResult.contractVersion().startsWith("sha256:"));

        // Contract bytes must be byte-for-byte identical across TS and Python targets.
        String tsContractRaw = Files.readString(tsOut.resolve(PolyglotCompiler.CONTRACT_RELATIVE_PATH));
        String pyContractRaw = Files.readString(pyOut.resolve(PolyglotCompiler.CONTRACT_RELATIVE_PATH));
        assertEquals(tsContractRaw, pyContractRaw);

        JsonObject contract = JsonParser.parseString(tsContractRaw).getAsJsonObject();
        assertEquals(3, contract.get("schemaVersion").getAsInt());
        assertEquals("InvoiceEngine", contract.get("pipelineId").getAsString());

        // Class-free invariant: NO canonical type carries runtimeClass.
        JsonObject canonicalTypes = contract.getAsJsonObject("canonicalTypes");
        assertTrue(canonicalTypes.size() >= 10);
        for (String name : canonicalTypes.keySet()) {
            JsonObject entry = canonicalTypes.getAsJsonObject(name);
            assertFalse(entry.has("runtimeClass"), "Canonical type '" + name + "' must omit runtimeClass");
            assertTrue(entry.has("definition"));
            assertTrue(entry.has("definitionFingerprint"));
        }

        // Root boundary invariant
        JsonObject rootBoundary = contract.getAsJsonObject("rootBoundary");
        assertEquals(1, rootBoundary.get("schemaVersion").getAsInt());
        assertEquals("InvoiceBatch", rootBoundary.get("inputCanonicalTypeId").getAsString());
        assertEquals("PaymentReceipt", rootBoundary.get("outputCanonicalTypeId").getAsString());

        // Manifest wiring invariant
        JsonObject manifest = JsonParser.parseString(
            Files.readString(tsOut.resolve(PolyglotCompiler.MANIFEST_RELATIVE_PATH))).getAsJsonObject();
        assertEquals("typescript", manifest.get("target").getAsString());
        JsonArray steps = manifest.getAsJsonArray("steps");
        assertEquals(6, steps.size());

        // Step 0: 1:1 foreign-function
        assertEquals("foreign-function", steps.get(0).getAsJsonObject().get("host").getAsString());
        assertEquals("/tpf/functions/validate-batch", steps.get(0).getAsJsonObject().get("endpointPath").getAsString());
        // Step 1: 1:N foreign-function
        assertEquals("/tpf/functions/split-lines/stream", steps.get(1).getAsJsonObject().get("endpointPath").getAsString());
        // Step 2: Tier-2 polyglot-connector query
        assertEquals("polyglot-connector", steps.get(2).getAsJsonObject().get("host").getAsString());
        assertEquals("/tpf/connector/query/ledger.rate", steps.get(2).getAsJsonObject().get("endpointPath").getAsString());
        // Step 3: Tier-1 engine-hosted http.client query (zero foreign code!)
        assertEquals("engine", steps.get(3).getAsJsonObject().get("host").getAsString());
        assertFalse(steps.get(3).getAsJsonObject().has("endpointPath"));
        // Step 4: N:1 foreign-function reducer
        assertEquals("/tpf/functions/sum-lines/reduce", steps.get(4).getAsJsonObject().get("endpointPath").getAsString());
        // Step 5: Tier-2 polyglot-connector command
        assertEquals("polyglot-connector", steps.get(5).getAsJsonObject().get("host").getAsString());
        assertEquals("/tpf/connector/command/ledger.settle/dispatch", steps.get(5).getAsJsonObject().get("endpointPath").getAsString());

        // Verify TS precision & ADR-0016 presence/nullability types
        String tsTypes = Files.readString(tsOut.resolve("types.ts"));
        assertTrue(tsTypes.contains("readonly batchSeq: Int64;"));
        assertTrue(tsTypes.contains("readonly grossAmount: DecimalString;"));
        assertTrue(tsTypes.contains("readonly memo?: string;"));
        assertTrue(tsTypes.contains("readonly overrideNote: string | null;"));
        assertTrue(tsTypes.contains("export type CurrencyCode = 'EUR' | 'USD';"));
        assertTrue(tsTypes.contains("readonly discriminator: 'approved'"));
        assertFalse(tsTypes.contains("fetchTax"), "Tier-1 engine connector must not appear in foreign TS handlers");

        // Verify Python precision & ADR-0016 presence/nullability types
        String pyTypes = Files.readString(pyOut.resolve("types.py"));
        assertTrue(pyTypes.contains("batchSeq: int"));
        assertTrue(pyTypes.contains("grossAmount: Decimal"));
        assertTrue(pyTypes.contains("memo: str | _UnsetType = UNSET"));
        assertTrue(pyTypes.contains("overrideNote: str | None"));
        assertTrue(pyTypes.contains("CurrencyCode: TypeAlias = Literal['EUR', 'USD']"));
        assertFalse(pyTypes.contains("fetch_tax"), "Tier-1 engine connector must not appear in foreign Python handlers");
    }

    @Test
    void rejectsManyToManyWithExplicitAdr0092Message(@TempDir Path temp) throws IOException {
        Path yaml = temp.resolve("m2m.yaml");
        Files.writeString(yaml, """
            version: 3
            appName: DuplexApp
            basePackage: com.example.duplex
            transport: LOCAL
            platform: COMPUTE
            types:
              Frame:
                fields:
                  - name: id
                    type: string
            steps:
              - name: Echo Stream
                cardinality: MANY_TO_MANY
                input: Frame
                output: Frame
            """);
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
            () -> PolyglotCompiler.compile(PolyglotCompiler.Target.TYPESCRIPT, yaml, temp.resolve("out")));
        assertTrue(err.getMessage().contains("MANY_TO_MANY"));
        assertTrue(err.getMessage().contains("ADR-0092"));
    }

    @Test
    void rejectsTypeDiscontinuityAndUnknownTypesAtBuildTime(@TempDir Path temp) throws IOException {
        Path discontinuous = temp.resolve("bad-chain.yaml");
        Files.writeString(discontinuous, """
            version: 3
            appName: BrokenChain
            basePackage: com.example.broken
            transport: LOCAL
            platform: COMPUTE
            types:
              A: { fields: [[id, string]] }
              B: { fields: [[id, string]] }
              C: { fields: [[id, string]] }
            steps:
              - name: First
                cardinality: ONE_TO_ONE
                input: A
                output: B
              - name: Second
                cardinality: ONE_TO_ONE
                input: C
                output: A
            """);
        RuntimeException chainErr = assertThrows(RuntimeException.class,
            () -> PolyglotCompiler.compile(PolyglotCompiler.Target.PYTHON, discontinuous, temp.resolve("out1")));
        assertTrue(chainErr.getMessage().contains("assignable") || chainErr.getMessage().contains("Type discontinuity"),
            chainErr.getMessage());

        Path unknownConnector = temp.resolve("bad-conn.yaml");
        Files.writeString(unknownConnector, """
            version: 3
            appName: MissingConn
            basePackage: com.example.missing
            transport: LOCAL
            platform: COMPUTE
            types:
              A: { fields: [[id, string]] }
            steps:
              - name: Query Missing
                kind: query
                using: nope
                operation: custom.find
                input: A
                output: A
            """);
        RuntimeException connErr = assertThrows(RuntimeException.class,
            () -> PolyglotCompiler.compile(PolyglotCompiler.Target.TYPESCRIPT, unknownConnector, temp.resolve("out2")));
        assertTrue(connErr.getMessage().contains("nope"), connErr.getMessage());
    }

    @Test
    void liveEndToEndTypeScriptHostPreservesExactInt64DecimalStreamingAndConnectors(@TempDir Path temp) throws Exception {
        Path yaml = temp.resolve("pipeline.yaml");
        Files.writeString(yaml, FULL_V3_PIPELINE_YAML);
        Path tsOut = temp.resolve("gen-ts");
        PolyglotCompiler.compile(PolyglotCompiler.Target.TYPESCRIPT, yaml, tsOut);

        // Write a tiny user app module implementing FunctionHandlers + ConnectorHandlers and starting host.ts.
        Path runner = tsOut.resolve("run.ts");
        Files.writeString(runner, """
            import { startAppHost } from './host.ts';
            import { TpfBusinessRejectionError } from './types.ts';
            import type { FunctionHandlers, ConnectorHandlers } from './types.ts';

            const handlers: FunctionHandlers = {
              async validateBatch(input) {
                if (input.batchSeq === 0n) {
                  throw new TpfBusinessRejectionError('ZERO_SEQ', 'batchSeq cannot be zero');
                }
                if (typeof input.batchSeq !== 'bigint') throw new Error('batchSeq was not bigint!');
                if (typeof input.grossAmount !== 'string') throw new Error('grossAmount was not exact DecimalString!');
                return {
                  ...input,
                  batchSeq: input.batchSeq + 1n,
                };
              },
              async *splitLines(input) {
                yield { lineSeq: input.batchSeq, amount: input.grossAmount };
                yield { lineSeq: input.batchSeq + 1n, amount: '0.000000001' };
              },
              async sumLines(inputs) {
                let count = 0;
                let seqSum = 0n;
                let lastAmount = '0';
                for await (const item of inputs) {
                  count += 1;
                  seqSum += item.lineSeq;
                  lastAmount = item.amount;
                }
                return { totalSeq: seqSum, totalAmount: lastAmount, lineCount: count };
              },
            };

            const connectors: ConnectorHandlers = {
              queries: {
                async ledgerRate(input, ctx) {
                  return {
                    outcome: 'FOUND',
                    payload: { lineSeq: input.lineSeq, amount: input.amount, rateSource: `secret:${ctx.secrets.apiKey}` },
                  };
                },
              },
              commands: {
                ledgerSettle: {
                  async dispatch(input, ctx) {
                    return {
                      outcome: 'SUCCEEDED',
                      payload: { receiptId: `tx:${ctx.dispatchIdentity.providerIdempotencyKey}:${ctx.secrets.apiKey}`, settledAmount: input.totalAmount },
                      receiptReference: 'ledger-rcpt-42',
                    };
                  },
                  async reconcile(ctx) {
                    return {
                      outcome: 'CONFIRMED_SUCCEEDED',
                      payload: { receiptId: `reconciled:${ctx.recoveryTargetIdentity}`, settledAmount: '99999999999999999999.123456789' },
                      receiptReference: 'ledger-rcpt-42',
                    };
                  },
                },
              },
            };

            const running = await startAppHost(handlers, connectors, { sharedSecret: process.env.TPF_SECRET! });
            console.log('READY ' + running.baseUrl);
            process.on('SIGTERM', async () => { await running.close(); process.exit(0); });
            """);

        runLiveHostVerification(List.of("node", runner.toString()), tsOut);
    }

    @Test
    void liveEndToEndPythonHostPreservesExactInt64DecimalStreamingAndConnectors(@TempDir Path temp) throws Exception {
        Path yaml = temp.resolve("pipeline.yaml");
        Files.writeString(yaml, FULL_V3_PIPELINE_YAML);
        Path pyPkg = temp.resolve("gen_py");
        PolyglotCompiler.compile(PolyglotCompiler.Target.PYTHON, yaml, pyPkg);
        Files.writeString(pyPkg.resolve("__init__.py"), "");

        Path runner = temp.resolve("run_py.py");
        Files.writeString(runner, """
            from decimal import Decimal
            import os, signal, sys, time
            from gen_py import host, types as t

            class AppHandlers:
                def validate_batch(self, inp: t.InvoiceBatch, ctx: t.FunctionContext) -> t.InvoiceBatch:
                    if inp.batchSeq == 0:
                        raise t.TpfBusinessRejectionError('ZERO_SEQ', 'batchSeq cannot be zero')
                    assert isinstance(inp.batchSeq, int)
                    assert isinstance(inp.grossAmount, Decimal)
                    return t.InvoiceBatch(
                        batchSeq=inp.batchSeq + 1,
                        currency=inp.currency,
                        decision=inp.decision,
                        feeAmount=inp.feeAmount,
                        grossAmount=inp.grossAmount,
                        overrideNote=inp.overrideNote,
                        tags=inp.tags,
                        memo=inp.memo,
                    )

                def split_lines(self, inp: t.InvoiceBatch, ctx: t.FunctionContext):
                    yield t.InvoiceLine(amount=inp.grossAmount, lineSeq=inp.batchSeq)
                    yield t.InvoiceLine(amount=Decimal('0.000000001'), lineSeq=inp.batchSeq + 1)

                def sum_lines(self, inputs, ctx: t.FunctionContext) -> t.BatchSummary:
                    count = 0
                    seq_sum = 0
                    last_amount = Decimal('0')
                    for item in inputs:
                        count += 1
                        seq_sum += item.lineSeq
                        last_amount = item.amount
                    return t.BatchSummary(lineCount=count, totalAmount=last_amount, totalSeq=seq_sum)

            class AppConnectors:
                def query_ledger_rate(self, inp: t.InvoiceLine, ctx: t.QueryConnectorContext):
                    return {
                        'outcome': 'FOUND',
                        'payload': t.EnrichedLine(
                            amount=inp.amount, lineSeq=inp.lineSeq, rateSource=f"secret:{ctx.secrets['apiKey']}"
                        ),
                    }

                def dispatch_ledger_settle(self, inp: t.BatchSummary, ctx: t.CommandConnectorContext):
                    return {
                        'outcome': 'SUCCEEDED',
                        'payload': t.PaymentReceipt(
                            receiptId=f"tx:{ctx.dispatch_identity.provider_idempotency_key}:{ctx.secrets['apiKey']}",
                            settledAmount=inp.totalAmount,
                        ),
                        'receiptReference': 'ledger-rcpt-42',
                    }

                def reconcile_ledger_settle(self, ctx: t.CommandReconcileContext):
                    return {
                        'outcome': 'CONFIRMED_SUCCEEDED',
                        'payload': t.PaymentReceipt(
                            receiptId=f"reconciled:{ctx.recovery_target_identity}",
                            settledAmount=Decimal('99999999999999999999.123456789'),
                        ),
                        'receiptReference': 'ledger-rcpt-42',
                    }

            running = host.start_app_host(AppHandlers(), AppConnectors(), shared_secret=os.environ['TPF_SECRET'])
            print('READY ' + running.base_url, flush=True)
            def stop(_sig, _frame):
                running.close()
                sys.exit(0)
            signal.signal(signal.SIGTERM, stop)
            while True:
                time.sleep(0.2)
            """);

        runLiveHostVerification(List.of("python3", runner.toString()), temp);
    }

    private static void runLiveHostVerification(List<String> command, Path workDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true);
        pb.environment().put("TPF_SECRET", SHARED_SECRET);
        Process proc = pb.start();
        try {
            String readyLine = null;
            try (var reader = proc.inputReader(StandardCharsets.UTF_8)) {
                long deadline = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < deadline) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    if (line.startsWith("READY ")) {
                        readyLine = line.substring("READY ".length()).trim();
                        break;
                    }
                }
            }
            assertNotNull(readyLine, "Foreign host failed to print READY URL");
            URI baseUri = URI.create(readyLine);
            HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

            // 1. Unary 1:1 with int64 > 2^53 (9007199254740993) and 29-digit decimal (99999999999999999999.123456789)
            //    + omitted optional field (`memo`) + explicit null nullable field (`overrideNote`) + union
            String unaryBody = """
                {"stepId":"validate-batch","stepIndex":0,"inputTypeId":"InvoiceBatch","outputTypeId":"InvoiceBatch","payload":{"batchSeq":9007199254740993,"grossAmount":99999999999999999999.123456789,"currency":"USD","overrideNote":null,"tags":["vip"],"feeAmount":12.34567890123456789,"decision":{"discriminator":"approved","value":{"code":"OK-1"}}},"context":{"versionTag":"v1"}}""";
            HttpResponse<String> unaryResp = sendSigned(client, baseUri, "/tpf/functions/validate-batch",
                unaryBody.getBytes(StandardCharsets.UTF_8), unaryBody.getBytes(StandardCharsets.UTF_8), "application/json");
            assertEquals(200, unaryResp.statusCode(), unaryResp.body());
            assertTrue(unaryResp.body().contains("9007199254740994"), "int64 + 1 must be exact (no float64 rounding): " + unaryResp.body());
            assertTrue(unaryResp.body().contains("99999999999999999999.123456789"), "decimal must be exact: " + unaryResp.body());
            assertTrue(unaryResp.body().contains("12.34567890123456789"), "aliased decimal must be exact: " + unaryResp.body());
            assertTrue(unaryResp.body().contains("\"overrideNote\":null"), "nullable null must be preserved: " + unaryResp.body());
            assertFalse(unaryResp.body().contains("\"memo\""), "omitted optional field must remain absent: " + unaryResp.body());

            // 2. Portable BUSINESS_REJECTION error classification
            String errBody = """
                {"stepId":"validate-batch","stepIndex":0,"inputTypeId":"InvoiceBatch","outputTypeId":"InvoiceBatch","payload":{"batchSeq":0,"grossAmount":1.00,"currency":"USD","overrideNote":null,"tags":[],"feeAmount":0.50,"decision":{"discriminator":"approved","value":{"code":"OK"}}},"context":{}}""";
            HttpResponse<String> errResp = sendSigned(client, baseUri, "/tpf/functions/validate-batch",
                errBody.getBytes(StandardCharsets.UTF_8), errBody.getBytes(StandardCharsets.UTF_8), "application/json");
            assertEquals(200, errResp.statusCode());
            JsonObject errJson = JsonParser.parseString(errResp.body()).getAsJsonObject();
            assertEquals("ERROR", errJson.get("status").getAsString());
            assertEquals("BUSINESS_REJECTION", errJson.get("disposition").getAsString());
            assertEquals("ZERO_SEQ", errJson.get("code").getAsString());

            // 3. Bad HMAC signature -> HTTP 401
            HttpRequest badSig = HttpRequest.newBuilder(baseUri.resolve("/tpf/functions/validate-batch"))
                .header("Content-Type", "application/json")
                .header(TransitionWorkerSignature.TIMESTAMP_HEADER, Instant.now().toString())
                .header(TransitionWorkerSignature.NONCE_HEADER, UUID.randomUUID().toString())
                .header(TransitionWorkerSignature.SIGNATURE_HEADER, "deadbeef")
                .POST(HttpRequest.BodyPublishers.ofString(unaryBody))
                .build();
            HttpResponse<String> badResp = client.send(badSig, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, badResp.statusCode());

            // 4. 1:N NDJSON stream
            String streamBody = """
                {"stepId":"split-lines","stepIndex":1,"inputTypeId":"InvoiceBatch","outputTypeId":"InvoiceLine","payload":{"batchSeq":9007199254740993,"grossAmount":99999999999999999999.123456789,"currency":"USD","overrideNote":null,"tags":[],"feeAmount":0.50,"decision":{"discriminator":"approved","value":{"code":"OK"}}}}""";
            HttpResponse<String> streamResp = sendSigned(client, baseUri, "/tpf/functions/split-lines/stream",
                streamBody.getBytes(StandardCharsets.UTF_8), streamBody.getBytes(StandardCharsets.UTF_8), "application/json");
            assertEquals(200, streamResp.statusCode());
            List<String> lines = streamResp.body().lines().filter(l -> !l.isBlank()).toList();
            assertEquals(3, lines.size(), "Expected 2 item frames + 1 complete sentinel: " + streamResp.body());
            assertTrue(lines.get(0).contains("\"event\":\"item\"") && lines.get(0).contains("9007199254740993"));
            assertTrue(lines.get(1).contains("\"event\":\"item\"") && lines.get(1).contains("9007199254740994"));
            assertTrue(lines.get(2).contains("\"event\":\"complete\""));

            // 5. N:1 NDJSON reduce (signed with STREAMING-NDJSON-V1)
            String reduceNdjson =
                "{\"event\":\"item\",\"inputTypeId\":\"EnrichedLine\",\"payload\":{\"lineSeq\":9007199254740993,\"amount\":1.25,\"rateSource\":\"s\"}}\n"
                    + "{\"event\":\"item\",\"inputTypeId\":\"EnrichedLine\",\"payload\":{\"lineSeq\":2,\"amount\":99999999999999999999.123456789,\"rateSource\":\"s\"}}\n"
                    + "{\"event\":\"complete\"}\n";
            HttpResponse<String> reduceResp = sendSigned(client, baseUri, "/tpf/functions/sum-lines/reduce",
                reduceNdjson.getBytes(StandardCharsets.UTF_8),
                "STREAMING-NDJSON-V1".getBytes(StandardCharsets.UTF_8),
                "application/x-ndjson");
            assertEquals(200, reduceResp.statusCode(), reduceResp.body());
            assertTrue(reduceResp.body().contains("9007199254740995"), "Exact int64 sum required: " + reduceResp.body());
            assertTrue(reduceResp.body().contains("99999999999999999999.123456789"), "Exact decimal required: " + reduceResp.body());
            assertTrue(reduceResp.body().contains("\"lineCount\":2"));

            // 6. Tier-2 Custom Polyglot Connector Query + Command Dispatch + Reconcile
            String queryBody = """
                {"operation":"ledger.rate","input":{"lineSeq":9007199254740993,"amount":42.50},"secrets":{"apiKey":"sk-live-99"}}""";
            HttpResponse<String> queryResp = sendSigned(client, baseUri, "/tpf/connector/query/ledger.rate",
                queryBody.getBytes(StandardCharsets.UTF_8), queryBody.getBytes(StandardCharsets.UTF_8), "application/json");
            assertEquals(200, queryResp.statusCode(), queryResp.body());
            assertTrue(queryResp.body().contains("\"outcome\":\"FOUND\""));
            assertTrue(queryResp.body().contains("secret:sk-live-99"));

            String cmdBody = """
                {"operation":"ledger.settle","input":{"totalSeq":9007199254740995,"totalAmount":99999999999999999999.123456789,"lineCount":2},"dispatchIdentity":{"commandId":"cmd-1","executionId":"exec-1","tenantId":"t1","stepId":"post-settlement","providerIdempotencyKey":"idem-77","attemptNumber":1},"secrets":{"apiKey":"sk-live-99"}}""";
            HttpResponse<String> cmdResp = sendSigned(client, baseUri, "/tpf/connector/command/ledger.settle/dispatch",
                cmdBody.getBytes(StandardCharsets.UTF_8), cmdBody.getBytes(StandardCharsets.UTF_8), "application/json");
            assertEquals(200, cmdResp.statusCode(), cmdResp.body());
            assertTrue(cmdResp.body().contains("\"outcome\":\"SUCCEEDED\""));
            assertTrue(cmdResp.body().contains("tx:idem-77:sk-live-99"));
            assertTrue(cmdResp.body().contains("99999999999999999999.123456789"));
            assertTrue(cmdResp.body().contains("ledger-rcpt-42"));

            String recBody = """
                {"operation":"ledger.settle","recoveryTargetIdentity":"target-ledger","dispatchIdentity":{"commandId":"cmd-1","executionId":"exec-1","tenantId":"t1","stepId":"post-settlement","providerIdempotencyKey":"idem-77","attemptNumber":1},"secrets":{"apiKey":"sk-live-99"}}""";
            HttpResponse<String> recResp = sendSigned(client, baseUri, "/tpf/connector/command/ledger.settle/reconcile",
                recBody.getBytes(StandardCharsets.UTF_8), recBody.getBytes(StandardCharsets.UTF_8), "application/json");
            assertEquals(200, recResp.statusCode(), recResp.body());
            assertTrue(recResp.body().contains("\"outcome\":\"CONFIRMED_SUCCEEDED\""));
            assertTrue(recResp.body().contains("reconciled:target-ledger"));
            assertTrue(recResp.body().contains("99999999999999999999.123456789"));
        } finally {
            proc.destroy();
            if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
        }
    }

    private static HttpResponse<String> sendSigned(
        HttpClient client,
        URI baseUri,
        String path,
        byte[] bodyBytes,
        byte[] bodyForSignature,
        String contentType
    ) throws Exception {
        String ts = Instant.now().toString();
        String nonce = UUID.randomUUID().toString();
        String sig = TransitionWorkerSignature.sign(SHARED_SECRET, "POST", path, ts, nonce, bodyForSignature);
        HttpRequest req = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", contentType)
            .header(TransitionWorkerSignature.TIMESTAMP_HEADER, ts)
            .header(TransitionWorkerSignature.NONCE_HEADER, nonce)
            .header(TransitionWorkerSignature.SIGNATURE_HEADER, sig)
            .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
