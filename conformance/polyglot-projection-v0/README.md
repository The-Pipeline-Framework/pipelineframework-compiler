# From a real v3 pipeline to a native research probe

This test tool asks whether a small compiler-owned projection is enough for the
examples repository's native A → B → C experiment. Start with this calculation:

```text
input {value: 3} → A adds 1 → B doubles → C subtracts 3 → {value: 5}
```

[fixtures/pipeline.yaml](fixtures/pipeline.yaml) declares the three logical steps
and their canonical `ProbeValue` input/output. It contains no application Java.
[fixtures/native-bindings.json](fixtures/native-bindings.json) supplies the native
handler symbols `a`, `b`, `c` and a **candidate research retry policy**: A may retry
twice, B and C may not retry. These settings are separate from DSL syntax and do
not claim that a production runtime has adopted this profile. Changing a binding
cannot change the pipeline's order, logical types, presence or nullability.

The producer really calls released `pipelineframework-dsl:26.11.1`'s
`PipelineTemplateConfigLoader.load`. That path resolves protocol vocabulary,
normalizes the v3 type model and validates logical contracts. The producer reads
its `PipelineTemplateConfig` / `PipelineTemplateTypeModel`; it does not implement
another YAML interpreter. Before loading, a shape-only gate rejects declarations
that this experiment cannot honor, including unknown keys that a general-purpose
loader could otherwise ignore. The linear profile additionally checks that every
immediate predecessor's output is assignable to the next input using the model.

## Generate and inspect one example

Use JDK 21 or later and Python 3.11 or later. Maven resolves only immutable released
dependencies into this worktree's `.m2/repository`. It does not build, install or
publish the production compiler, or alter another repository's dependency reactor.

From the compiler repository root:

```sh
python3 conformance/polyglot-projection-v0/run.py
python3 -m venv target/polyglot-projection-v0/test-venv
target/polyglot-projection-v0/test-venv/bin/python -m pip install -r conformance/polyglot-projection-v0/requirements-test.txt
target/polyglot-projection-v0/test-venv/bin/python -B -m unittest discover -s conformance/polyglot-projection-v0 -p 'test_*.py'
```

After the first resolution, `run.py --skip-resolve` reuses that isolated cache.
CI runs this bounded check separately from the canonical compiler suite. It
generates into `target`, leaving committed output untouched before the tests
compare both fixture packages and the Java reference byte for byte.
The test environment pins `jsonschema` 4.25.1 to validate both generated artifacts
against JSON Schema draft 2020-12. A deliberately narrowed retry maximum must
reject both artifacts. This dependency is used only by conformance tests;
generation remains standard-library Python plus the released Java dependencies.
Expect `PROJECTION_PASS:polyglot-abc` and
`JAVA_REFERENCE_PASS:7 scenarios,2 overlapping contexts`. The reference reads
the same projection and fixed [scenarios](fixtures/scenarios.json) as the native
consumers. Its small CompletableFuture shell independently checks success,
transient/terminal failure, retry exhaustion, cancellation, and overlapping
execution/trace identities. **It is a handwritten Java oracle, not the production
`StepOneToOne` path or a proof of Java-runtime equivalence.**

Read the results in [generated](generated):

| File | Purpose |
| --- | --- |
| `projection.json` | Inspectable ordered steps, canonical record fields, profile and policy requirements, input/source identities |
| `models.ts` | Generated record interfaces, logical type map, context and exact async handler registry |
| `models.py` | Generated TypedDict records, context and async callable registry annotations |
| `SHA256SUMS` | SHA-256 of those three exact output files |
| `java-reference.json` / `.sha256` | Independently checked Java outcomes and exact reference-report digest |

The array order in `steps` is the executable linear order: each output is supplied
to the next entry. A consumer must validate the whole artifact, required
capabilities, bindings and canonical data before business invocation; it cannot
infer missing policies or skip an unknown feature. The closed JSON shape is
documented in [projection.schema.json](projection.schema.json). Cross-field
requirements, unique identities and exact handler matches also need validation.
This schema is research documentation, not a new production Release manifest.

`PresenceProbe` is a separate, unused canonical record that exercises all four
presence/nullability combinations. Generated `Int32` is a TypeScript `number`
alias and Python uses `int`; runtime checks must enforce signed 32-bit range and
reject boolean/coerced/fractional values. Use TypeScript strict mode with
`exactOptionalPropertyTypes` to check omission independently from nullability.
Logical ids come from the v3 model, never Java class names or native symbols.
They are not protobuf/union tags or a new compiler contract-hash algorithm.

## Change the DSL and see generation change

Copy the fixture, rename `ProbeValue` to `ChangedValue` in both authored files,
then run:

```sh
python3 conformance/polyglot-projection-v0/run.py --skip-resolve \
  --dsl /absolute/path/pipeline.yaml \
  --bindings /absolute/path/native-bindings.json \
  --output /absolute/path/generated
```

The output's logical type ids, generated declarations and DSL digest change.
The focused tests perform that change using the real compact v3 field syntax
`[value?, int32?]` and check normalized optional/nullable output. They also try a
missing canonical type, an incompatible link, a flow whose next input is supplied
only by an earlier step, missing/duplicate/extra/wrongly typed handlers, duplicate
logical steps, unsafe generated names, and unsupported feature/policy declarations.
Each rejected input must name `PROJECTION_REJECT:<reason>:` and produce no output.
The examples-owned proof supplies actual TypeScript compiler diagnostics for
wrong/missing handlers and presence/nullability; these Java/Python tests do not
claim to perform TypeScript semantic checking.

[fixtures/changed-order.yaml](fixtures/changed-order.yaml) is a second real authored
input with B → A → C. Its regenerated order gives `3 * 2 + 1 - 3 = 4`, using the
same handler functions and unchanged binding array. The examples consumer can
load [generated-changed-order](generated-changed-order) directly. Reproduce it with:

```sh
python3 conformance/polyglot-projection-v0/run.py --skip-resolve --projection-only \
  --dsl conformance/polyglot-projection-v0/fixtures/changed-order.yaml \
  --output conformance/polyglot-projection-v0/generated-changed-order
```

`--projection-only` omits the baseline a,b,c-specific Java oracle. Every successful
projection generation clears a previous reference report and checksum before an
oracle is attempted; failed oracle validation leaves no stale reference. The changed-order artifact has
the same closed profile; it does not claim the baseline scenario answers apply.

Candidate configuration numbers follow the shared profile's exact numerical
value rule: `2.0` and `2e0` mean two retries, while booleans, numeric strings,
fractions and near integers such as `2.0000000000000001` reject. This probe's DSL
admission requires an explicit integer `version: 3` and explicit LOCAL/COMPUTE;
it never relies on general-loader transport/platform fallbacks.

## Generate the explicit unary policy binding

The same producer also supports the candidate `tpf-polyglot-unary-policy/1`
profile from [contracts #52](https://github.com/The-Pipeline-Framework/pipelineframework-contracts/issues/52).
Select it in [policy-bindings.json](fixtures/policy-bindings.json). This is an
explicit research binding choice, not new retry syntax in the DSL.

The emitted `policy` object names the failure mapping, single retry owner, result
mapping, cancellation ordering and explicit context binding. It rejects independent
legacy policy and recovery. A consumer must admit these requirements before calling
business code. An unfamiliar profile or policy must fail; the consumer cannot fall
back to the v0 interpretation.

```sh
python3 conformance/polyglot-projection-v0/run.py --skip-resolve --projection-only \
  --bindings conformance/polyglot-projection-v0/fixtures/policy-bindings.json \
  --output conformance/polyglot-projection-v0/generated-policy/baseline

# Same functions, but change their order using the authored DSL.
python3 conformance/polyglot-projection-v0/run.py --skip-resolve --projection-only \
  --dsl conformance/polyglot-projection-v0/fixtures/changed-order.yaml \
  --bindings conformance/polyglot-projection-v0/fixtures/policy-bindings.json \
  --output conformance/polyglot-projection-v0/generated-policy/changed-order

# Keep the DSL order and give B one retry through the explicit research binding.
python3 conformance/polyglot-projection-v0/run.py --skip-resolve --projection-only \
  --bindings conformance/polyglot-projection-v0/fixtures/policy-downstream-retry-bindings.json \
  --output conformance/polyglot-projection-v0/generated-policy/downstream-retry
```

The generated order changes A→B→C to B→A→C; the generated budgets change B from
zero to one retry. Neither alteration comes from handwritten consumer metadata.
The examples-owned live binding proof consumes these three packages and uses the
existing Java executor alongside actual Node/Python function calls. It owns execution
qualification; this producer proves generation, declaration and rejection behaviour.
`--projection-only` deliberately skips the older v0 Java reference model.

This v1 executable binding is closed to three linear steps over one required,
non-null `value:int32` record. Additional `PresenceProbe` declarations continue to
exercise generated type syntax, but are not executable nullable whole-result support.
Unsupported executable shapes fail during generation. These experimental limits do
not narrow the eventual streaming, Connector or Await requirements.

The v0 profile retains its original semantics and document shape: it has no `policy`
object. Regeneration changes its generator-source digest as expected, without
retrofitting v1 settlement rules into historical v0 observations. The existing schema
checks both profiles and requires the policy object only for v1. CI reproduces all
three v1 packages byte for byte and rejects missing or altered policy declarations.

## Boundaries and provenance

Supported here: records of scalar `int32`, required/optional and non-null/nullable
fields, safe shared identifiers, a finite nonempty linear ONE_TO_ONE flow, COMPUTE,
LOCAL, direct execution, per-step candidate retry limits 0–2, cancellation and
allowlisted context. The worked executable binding is a/b/c over `ProbeValue`.
Unknown/unsafe names fail instead of introducing a public name-mangling scheme.

Streaming, branching, nested invocation, Await, Query/Command, connectors, plugins,
representation providers, persistence, caching, FUNCTION, REST/gRPC step placement,
QUEUE_ASYNC, provider credentials and durable authority are outside this experiment
and reject at admission. Those requirements are not removed from TPF's roadmap.
Native output does not load YAML. There is no JVM executor in the native journey;
this **contributor generation tool and Java oracle do require Java locally**.
No-local-JVM changed-project generation remains an unimplemented tooling gate.

Provenance records exact DSL and native-binding byte digests, the loaded DSL/model
JAR digests and immutable contracts version. `generatorTreeSha256` hashes sorted
tool source names and bytes (`*.java`, `run.py`, `dependencies.pom.xml`), separated
by zero bytes; no timestamp, absolute path or self-referential commit hash enters
the result. An artifact digest is distinct from those source identities.
The runner captures tool sources once, compiles the staged Java bytes into a
separate prepared class directory, and retains that snapshot's digest with its
classpath. Editing source after preparation does not stamp that new source's
identity onto older classes; preparing again uses the changed bytes and digest.
The tests prove both cases using temporary source copies. The runner also stages
input bytes before the loader's two reads. Generated files
are byte-for-byte reproducible; fixtures and tests remain inspectable research
evidence, not a public SDK, compiler CLI or production native runtime.

Work relates to [compiler #46](https://github.com/The-Pipeline-Framework/pipelineframework-compiler/issues/46),
[examples #27](https://github.com/The-Pipeline-Framework/pipelineframework-examples/issues/27),
[contracts #52](https://github.com/The-Pipeline-Framework/pipelineframework-contracts/issues/52)
and [draft ADR-0088](https://github.com/The-Pipeline-Framework/pipelineframework/pull/1082).
This closes only a bounded projection/proof gate. It does not complete those issues,
select a production runtime, provide remote build/authentication/tooling, or authorize
merge, release publication or deployment.

Recorded local evidence (2026-11-06): compiler baseline
`9a03f7a9b3d78d72c0ac3ca3621962b2788c2e97`, OpenJDK 21.0.9, Python 3.14.6,
released contracts 26.11.1 resolved from Maven Central. Both fixture packages
regenerated; all 15 focused tests passed, including the Java oracle's seven fixed
cases, two forced-overlap contexts and deliberately wrong-answer rejection.
The canonical production compiler suite was not run for this standalone probe;
the existing CI suite remains unchanged alongside the new bounded job.
GitNexus supplied loader/model orientation. Initial new-file analysis was unavailable;
a later temporary worktree overlay analysed the new producer and reference flows.
Change analysis reported HIGH risk and remained partial on the three generated
checksum descriptors after retry. Those exact bytes are checked by regeneration
tests; this is not a clean graph verdict. Producer and launcher taint queries found
no modelled findings, and producer control-dependence evidence was checked against
source. Independent source review and executable falsification supplement the
graph's limits. No managed index was refreshed or rebuilt.
