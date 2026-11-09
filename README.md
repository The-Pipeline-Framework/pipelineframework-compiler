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
in the separate [census collector repository](https://github.com/The-Pipeline-Framework/pipelineframework-census).
It owns the accepted fields, retention policy, and collector privacy controls.
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
retained state and makes HTTP requests only after Maven exits. If CI kills
it, the next build with the same retained state can retry. CI and container
builds need `TPF_CENSUS_STATE_PERSISTENT=true` and genuinely retained local
storage. Expired events are discarded.

### Verification

The forked-javac probe loads the packaged Maven adapter with an approved
release marker in a temporary JAR and routes it to a literal loopback
collector. It leaves `TPF_CENSUS_ENABLED` unset in the reporting arm and
sets it to `false` for the control. It verifies that acknowledgements are
recorded after Maven exits.

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
