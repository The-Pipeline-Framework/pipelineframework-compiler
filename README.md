# The Pipeline Framework Compiler

## Test coverage

`./mvnw clean verify` writes the JaCoCo report to `target/site/jacoco/`.
CI publishes the HTML/XML report and execution data as the `compiler-coverage`
artifact for inspection.

Coverage is currently informational. No code is excluded from the report, and
no percentage threshold is enforced until the repository has an observed,
reviewed baseline. Unit tests continue to run through Surefire during `test`;
integration or end-to-end tests must remain on the Failsafe lifecycle so the
coverage setup does not conflate their execution with unit tests.

This repository builds `org.pipelineframework:pipelineframework-compiler`, the framework-neutral compiler and Java domain generation toolchain. The compiler owns the production JSR-269 processor, semantic phases, and generated-source renderers. Its inputs and outputs use published TPF contracts from the API, semantic model, DSL, runtime-core, runtime protocol, and representation-provider API artifacts. Runtime and deployment implementations remain outside this artifact.

The compiler owns:

- the production JSR-269 build host and `@PipelineStep` discovery;
- DSL parsing and normalization into the shared semantic model;
- topology, type, cardinality, recursion, callable, capability, placement, and deployability validation;
- Pipeline contract and release/provenance metadata generation;
- generated Java, protobuf, adapter, and runtime-boundary source renderers.

Its public inputs and outputs are released artifacts from `pipelineframework-contracts`. It does not load a Quarkus
or Spring runtime implementation. Quarkus deployment consumes this compiler as build tooling; it does not own the
compiler's semantics. A future Jandex source adapter may feed the same semantic model as JSR-269, but it must not
create a second semantic path.

Build with an isolated Maven repository:

```sh
./mvnw clean verify -Dmaven.repo.local="$PWD/.m2/repository"
```

Set `-Dtpf.version=<version>` only when deliberately testing another published TPF contract set. Use the
`central-publishing` profile only to sign and deploy the canonical artifact. For the component map, see
[TPF Components and Repositories](https://pipelineframework.org/architecture/components-and-repositories).

## Generate Node bindings without local Java

The compiler can generate TypeScript models, a typed handler registry and application API, and an ordered
execution projection for the bounded `tpf-polyglot-unary-policy/1` profile. The generated application
delegates execution to the runtime interface it exports. A compatible Node runtime is tracked in
[runtime #84](https://github.com/The-Pipeline-Framework/pipelineframework-runtime/issues/84). The generation
workflow covers the compiler output and its retrieval. Streaming, Connectors, Await, and distributed
execution are outside this profile.

### Author the inputs

Use DSL version 3 with `transport: LOCAL`, `platform: COMPUTE`, and exactly three linear
`ONE_TO_ONE` steps. Declare four distinct canonical record types, each with one required
`value: int32` field, for the pipeline input, the two intermediate values, and the final output.
Provide `appName`, `basePackage`, and the pipeline input/output contract.

In `native-bindings.json`, set `schemaVersion` to `1`, `profile` to
`tpf-polyglot-unary-policy/1`, and `pipelineId` to the DSL's `appName`. Supply one entry in
`handlers` per authored step. Each entry names the exact authored `step`, a distinct TypeScript
`handler` identifier, its canonical `inputType` and `outputType` names, and a `retryLimit`
from 0 through 2. The compiler derives execution order from the DSL. It emits the
`compiler-node-local` projection format. Use that format with the generated application API.

### Set up the generation workflow

Use a GitHub application repository with Actions enabled. Save the following workflow as
`.github/workflows/tpf-node-generation.yml` on the repository's default branch and on the branch or tag you
dispatch. Replace `COMPILER_SHA` in the `uses` line with the full commit SHA of the compiler revision you
trust. The workflow must be present at that revision in the public compiler repository.

```yaml
name: Generate TPF Node bindings

on:
  workflow_dispatch:
    inputs:
      source_sha:
        description: Full application commit SHA
        type: string
        required: true
      compiler_sha:
        description: Full compiler SHA matching the reusable workflow pin
        type: string
        required: true
      project:
        description: Repository-relative project directory
        type: string
        default: '.'

permissions:
  contents: read

jobs:
  generate:
    uses: The-Pipeline-Framework/pipelineframework-compiler/.github/workflows/tpf-node-generation.yml@COMPILER_SHA
    with:
      source_sha: ${{ inputs.source_sha }}
      compiler_sha: ${{ inputs.compiler_sha }}
      project: ${{ inputs.project }}
```

The application repository and its caller workflow are part of the trust boundary. The reusable job uses
a hosted runner and a read-only token. It checks out both exact commits, reads two regular Git blobs
named `pipeline.yaml` and `native-bindings.json`, and builds the compiler with the pinned Java and Maven
toolchain and released Contracts dependencies. It runs no application build scripts and requires no
application secrets, cross-repository token or TPF Cloud account. Keep credentials out of committed
inputs and omit `secrets: inherit` from the caller.

### Generate and retrieve

On the workstation, use Git and GitHub CLI 2.100 or newer. Run the client with Node.js 22 or newer
or with CPython 3.12. The Python client is tested on CPython 3.12.15 on Linux. Sign in with
`gh auth login --hostname github.com` using an account authorised to dispatch workflows and retrieve
artifacts in the application repository. The supported sign-in uses GitHub CLI's OAuth flow because
`gh run watch` needs Checks access. Clone the compiler separately and check it out at the same
`COMPILER_SHA` used in the workflow. [GitHub CLI run watch](https://cli.github.com/manual/gh_run_watch)

After editing the pipeline or handler bindings, commit and push your changes to an authorised branch
or tag. Run from the application checkout at the commit being generated.

```sh
node /path/to/pinned/compiler/scripts/remote-node-generation.mjs \
  OWNER/APP BRANCH_OR_TAG "$(git rev-parse HEAD)" COMPILER_SHA ./generated-next
```

The Python client dispatches the same workflow and retrieves the same TypeScript and projection files.
It runs on Python's standard library, Git, and GitHub CLI. Java and Node run on the remote build host.
Use the Node binding instructions below to consume the result.

```sh
python3.12 /path/to/pinned/compiler/scripts/remote_node_generation.py \
  OWNER/APP BRANCH_OR_TAG "$(git rev-parse HEAD)" COMPILER_SHA ./generated-next
```

For a project in a subdirectory, append its repository-relative path after the output directory.
The output directory must be new and its parent must exist. The tool sends the branch or tag and
commit identities to Actions. It obtains the run ID directly from dispatch, verifies the exact source
and compiler workflow revision, checks the output digests and the projection's input hashes, then
installs the verified files together. If the run fails or its output differs, the command exits
with an error. Inspect the linked run and its diagnostics, fix the inputs or host configuration,
and dispatch again. A rerun or expired artifact requires a new dispatch.

Use the retrieved `application.ts` and `models.ts` in the application's TypeScript project and type-check the
handler registry with the project's pinned TypeScript toolchain. For an npm project with a committed
lockfile, install dependencies with `npm ci` and run the project's type-check script, or
`./node_modules/.bin/tsc --noEmit` with those files included in `tsconfig.json`. The local type check uses Node.
Changing step order can require updating the canonical input/output types on the affected edges and
updating the corresponding binding declarations.

For bindings named `normalize`, `apply`, and `finish`, the generated registry checks each function
against its declared types. The application accepts a compatible runtime through the generated
`NodeLocalRuntime` interface.

```ts
import { createApplication, type HandlerRegistry, type NodeLocalRuntime } from "./generated-next/application.js";

const handlers = {
  normalize: input => ({ kind: "VALUE", value: { value: input.value } }),
  apply: input => ({ kind: "VALUE", value: { value: input.value + 1 } }),
  finish: input => ({ kind: "VALUE", value: { value: input.value } }),
} satisfies HandlerRegistry;

export const bind = (runtime: NodeLocalRuntime) => createApplication(runtime, handlers);
```

The output includes `projection.json`, `diagnostics.txt`, and `generation-receipt.json` alongside the
generated TypeScript. The receipt records source and compiler commits, Maven dependency versions and
JAR digests, the profile, and generated file digests. These checks establish integrity for the
selected build result. The receipt carries generation metadata only. TPF Release identity comes from
existing Release packaging and verification.

An alternative build host can invoke the compiler directly with Java 21 and the packaged compiler
and its runtime dependency JARs on the classpath.

```sh
java --enable-preview -cp "$COMPILER_CLASSPATH" \
  org.pipelineframework.compiler.node.NodeLocalCompiler pipeline.yaml native-bindings.json ./generated-next
```

Both client suites exercise the same run, receipt, and artifact acceptance cases.

```sh
node --test scripts/test-node-generation.mjs
python3.12 -B -m unittest discover -s scripts -p test_remote_node_generation.py
```

## Canonical union REST representation

For a version 3 canonical union whose variants carry named record payloads, REST generation emits a typed
`<Union>Dto` envelope. Its `discriminator` is the authored variant key, and exactly one JSON property named for
that variant contains its record DTO; every other variant property is null. The envelope rejects missing, unknown,
or mismatched discriminators and missing or multiple payloads during JSON deserialization. The generated REST mapper
converts through the corresponding canonical sealed union wrapper and the existing record REST mappers, including
nested records. No Java class name or polymorphic type metadata is sent on the wire.

The Java union variant must be a nested record with one `value` component whose type is the named record payload;
the compiler reads that component rather than inferring a Java package. A variant key of `discriminator` is
reserved because it would collide with the envelope tag. Other canonical union payload shapes require a separate
REST contract and are rejected with a compiler diagnostic. Protobuf/GRPC union generation remains independent.
The Await completion integration covered here is the no-projector REST route: its final union DTO is emitted from
the canonical final output and carried as the wire value. An explicit union completion payload with a projector
needs separate contract and application-level verification.

## System-test candidates

`TPF Candidate Build` runs at the exact pull-request or `main` SHA with read-only permissions and no secrets. It
assigns the compiler a commit-specific `-pr.<number>.<sha12>` or `-main.<sha12>` version, verifies it, and uploads
only the allowlisted compiler POM/JAR files and preliminary metadata. The trusted `TPF Candidate Publish` workflow
validates that build and the current head, publishes those files to this repository's GitHub Packages registry,
then dispatches `tpf-candidate-v1` to the coordination repository. It does not execute project or fork code.

Fork pull requests require `safe-to-system-test`. Configure `SYSTEM_TEST_APP_ID` as a repository variable and
`SYSTEM_TEST_APP_PRIVATE_KEY` as a repository secret for the coordination GitHub App. Candidate publication uses no
Maven Central credentials or GPG key. Candidate versioning does not rewrite the released `tpf.version` contract
dependency.


## Build census

The compiler owns build-only census projection. The Maven integration owns
retained state and delivery. Local preview is available with javac options
`-Atpf.census.mode=preview` and `-Atpf.census.preview=/private/census-preview.json`.
Opt out with `TPF_CENSUS_ENABLED=false`, JVM property `tpf.census.enabled=false`,
or processor option `-Atpf.census.enabled=false`. Any disabling source wins, including
invalid values. Use `TPF_CENSUS_STATE_DIR` to select a managed state directory.
Known CI/container environments require `TPF_CENSUS_STATE_PERSISTENT=true` and
retained storage even for preview.

Choose a fresh preview filename outside source and packaged output. Existing files are
never replaced; preview requires an identifiable compiler output directory. Resolved
paths containing any of these reserved components are rejected: `src`, `resources`,
`classes`, `test-classes`, `target`, `build`, `generated-sources`,
`generated-test-sources`, and `META-INF`.
The event covers a successful TPF compiler pass. javac can still report errors after
annotation processing, and packaging and tests run separately.

The packaged schema is `META-INF/tpf/census-v1.schema.json`; protocol/collector source lives
in the census collector repository. See the [observation schema](https://github.com/The-Pipeline-Framework/pipelineframework-census/blob/dc320be0e7549abebf2912b46e0ea5c9a4642d5a/protocol/v1.schema.json)
and [privacy runbook](https://github.com/The-Pipeline-Framework/pipelineframework-census/blob/dc320be0e7549abebf2912b46e0ea5c9a4642d5a/docs/privacy-runbook.md)
for the accepted fields, retention policy, and collector privacy controls.
No census dependencies or reporting code are added to generated applications or runtime paths.

### Maven integration

Maven 3.9 with JDK 21 or later on Linux or macOS can load the compiler as a
core extension. Add it to the application's `.mvn/extensions.xml` alongside
any existing extensions. Use the exact compiler version used by the application.
The extension does not replace the existing annotation processor configuration.

```xml
<extensions>
  <extension>
    <groupId>org.pipelineframework</groupId>
    <artifactId>pipelineframework-compiler</artifactId>
    <version>26.11.2</version>
  </extension>
</extensions>
```

For an approved release, reporting is on by default for eligible builds using
the extension. Leave `TPF_CENSUS_ENABLED` unset to use that default, or set
`TPF_CENSUS_ENABLED=false` to disable it. The tagged compiler release workflow
embeds the public reporting gate only after `TPF_CENSUS_PUBLIC_RELEASE_APPROVED`
is set to `true` by the release owner. Snapshots and ordinary builds leave the
gate off. Standalone javac has local preview but no automatic delivery.

The extension receives bounded local messages during compilation and writes
them to an owner-only outbox on a background thread. Maven allows at most
125 ms for the final local flush. A separate sender starts when an eligible
observation is pending or the local flush reaches its deadline. It checks
retained state after the Maven session ends. If CI kills
it, the next build with the same retained state can retry. CI and container
builds need `TPF_CENSUS_STATE_PERSISTENT=true` and genuinely retained local
storage. Expired events are discarded.

### Verification

The forked-javac probe loads the packaged Maven adapter with an approved
release marker in a temporary JAR and routes it to a literal loopback
collector. It leaves `TPF_CENSUS_ENABLED` unset in the reporting arm and
sets it to `false` for the control. It verifies that acknowledgements are
recorded independently of the Maven session.

The parallel probe runs six independent modules with Maven `-T 4`, reaps the
sender, and verifies that the next build delivers all six without recompiling.
It also checks that disposable CI creates no state.

```sh
TPF_CENSUS_ENABLED=false ./mvnw -Dmaven.repo.local="$PWD/.m2/repository" \
  -Dtest=CensusForkedJavacTest -Dtpf.census.senderTrials=10 -Dtpf.census.parallel=true test
```

The nested builds use a warmed Maven repository in offline mode. CI runs
these probes with a smaller timing sample. The production endpoint is never
used by the probes.

On Apple silicon with JDK 21 and Maven 3.9.11, ten paired forked builds all
delivered after exit. The median added Maven wall time was 19 ms, down from
501 ms in the prototype. The paired p95 was 184 ms and the javac final-round
p95 was 3.68 ms. These are fixture measurements on one machine.
