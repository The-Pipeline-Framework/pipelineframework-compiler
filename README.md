# The Pipeline Framework Compiler

This repository publishes `org.pipelineframework:pipelineframework-compiler`, the framework-neutral compiler and
Java domain generation toolchain for TPF.

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
