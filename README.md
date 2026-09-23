# Pipeline Framework Compiler

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

The standalone repository is an extraction surface for the compiler module's `src` tree. Keep processor service registration, tests, test resources, and binary fixtures alongside that source. Third-party dependency versions are pinned here so this build does not depend on a framework BOM.

This repository was extracted from `The-Pipeline-Framework/pipelineframework` after commit `3f3f467749a56c9a480cccfe8ef68c1603512350`, which completed the compiler boundary prerequisites. Subsequent compiler history and releases are owned here.

The artifact and TPF contract dependencies currently target `26.9.4-SNAPSHOT`. To build against local snapshots, first ensure those TPF contract artifacts are available in the selected Maven repository, then run:

```sh
./mvnw clean verify -Dmaven.repo.local="$PWD/.m2/repository"
```

Set `-Dtpf.version=<version>` when intentionally testing against another published TPF contract set. The compiler artifact version remains controlled by this repository's project version.
