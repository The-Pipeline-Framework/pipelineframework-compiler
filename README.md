# Pipeline Framework Compiler

This repository builds `org.pipelineframework:pipelineframework-compiler`, the framework-neutral compiler and Java domain generation toolchain. The compiler owns the production JSR-269 processor, semantic phases, and generated-source renderers. Its inputs and outputs use published TPF contracts from the API, semantic model, DSL, runtime-core, runtime protocol, and representation-provider API artifacts. Runtime and deployment implementations remain outside this artifact.

The standalone repository is an extraction surface for the compiler module's `src` tree. Keep processor service registration, tests, test resources, and binary fixtures alongside that source. Third-party dependency versions are pinned here so this build does not depend on a framework BOM.

This repository was extracted from `The-Pipeline-Framework/pipelineframework` after commit `3f3f467749a56c9a480cccfe8ef68c1603512350`, which completed the compiler boundary prerequisites. Subsequent compiler history and releases are owned here.

The artifact and TPF contract dependencies currently target `26.9.4-SNAPSHOT`. To build against local snapshots, first ensure those TPF contract artifacts are available in the selected Maven repository, then run:

```sh
./mvnw clean verify -Dmaven.repo.local="$PWD/.m2/repository"
```

Set `-Dtpf.version=<version>` when intentionally testing against another published TPF contract set. The compiler artifact version remains controlled by this repository's project version.

## System-test candidates

The `TPF Candidate Build` workflow runs with read-only permissions and no secrets. It checks out the
exact PR head (or main push), assigns the compiler artifact a commit-specific
`-pr.<number>.<sha12>` or `-main.<sha12>` version, verifies and installs it, then uploads only the
`org.pipelineframework:pipelineframework-compiler:jar` files and preliminary build metadata. The
separate, trusted `TPF Candidate Publish` workflow validates that build and the current PR head before
publishing the allowlisted POM and JAR to this repository's GitHub Packages Maven registry. It then
creates the final `tpf-candidate-manifest` and `tpf-candidate-event` artifacts and dispatches
`tpf-candidate-v1` to the framework repository. It does not run project or fork code.

Fork pull requests require the `safe-to-system-test` label before candidate publication. Configure
`SYSTEM_TEST_APP_ID` as a repository variable and `SYSTEM_TEST_APP_PRIVATE_KEY` as a repository secret for a GitHub App
installed on `The-Pipeline-Framework/pipelineframework` with Contents write permission. The package
publisher uses the workflow `GITHUB_TOKEN` with Packages write permission; no Central credentials or
GPG key are used by candidate publication. Contracts remain released dependencies: candidate versioning
changes the compiler project version without rewriting the `tpf.version` contract version property.
