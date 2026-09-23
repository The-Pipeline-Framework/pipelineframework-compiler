# Compiler Repository Instructions

This repository owns the standalone TPF compiler artifact: the production JSR-269 processor, compilation-target
discovery, semantic phases, validation, renderers, processor registration, and compiler tests.

## Boundary

- `@PipelineStep` and other authored discovery contracts come from `pipelineframework-api`; do not add Quarkus or
  runtime dependencies to discover authored compilation targets.
- Normalize every source host into the same contracts-owned semantic model. JSR-269 is the production host. A
  future Jandex adapter is an enhancement, not a second semantic implementation.
- Keep framework-neutral compiler semantics here. Keep Quarkus build integration and runtime/deployment mechanics
  in `pipelineframework-runtime`.
- Do not load a TPF runtime implementation to compile an application.
- Shared API, DSL, semantic model, runtime protocol, and representation-provider types are released dependencies.
  Do not mirror their source here.

## Cross-repository changes

Update canonical documentation or an ADR in `pipelineframework` when a change alters authored syntax, semantic
ownership, generated contracts, or compiler/runtime compatibility. Use the GitNexus `tpf` group for cross-repository
impact and verify findings in the owning worktree.

## Build and publication

Owner-local verification is the first gate. `TPF Candidate Build` and the trusted publisher create an immutable,
commit-specific compiler candidate for the coordination repository; `tpf/system-tests` records downstream evidence
on that exact source SHA. Use a compatibility set for coordinated repository changes, and require a green full
train for formal BOM or release promotion. Keep the stable owner suite command in `.github/tpf-system-tests.json`.

Always use the repository-local Maven cache:

```sh
./mvnw <goals> -Dmaven.repo.local="$PWD/.m2/repository"
```

Do not introduce Maven profiles except `central-publishing`. It may attach, sign, and deploy the canonical artifact
but must not select another source universe or compilation model.

Do not commit, push, publish, or change another repository unless explicitly requested.
