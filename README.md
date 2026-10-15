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
