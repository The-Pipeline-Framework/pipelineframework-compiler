# Repository instructions

This repository builds the standalone `pipelineframework-compiler` artifact. Keep compiler implementation, JSR-269 service registration, and compiler tests here. Shared API, semantic model, DSL, runtime-core, runtime protocol, and representation-provider contracts are dependencies; runtime and deployment implementations do not belong in this repository.

## Cross-repository system tests

Owner-local verification is the first gate. `.github/tpf-system-tests.json` owns the stable compiler suite command.
`TPF Candidate Build` and the trusted publisher create an immutable, commit-specific compiler candidate;
`tpf/system-tests` records downstream evidence on that exact source SHA.

For a coordinated compiler/runtime or contracts/compiler change, wait for `TPF Candidate Publish` to succeed for
the current head SHA of every participating pull request. Then run `TPF System Tests — Compatibility Set` in
`The-Pipeline-Framework/pipelineframework` with one stable set ID and the pull-request URLs. Any new commit
invalidates the previous set: wait for its new candidate publisher and dispatch again. Require the same
`tpf/system-tests` success on every participating SHA. Do not substitute snapshots, branch heads, source checkouts
or a composite Maven reactor. See the canonical
[cross-repository system-test runbook](https://github.com/The-Pipeline-Framework/pipelineframework/blob/main/docs/evolve/cross-repository-system-tests.md).

Repository setup requires repository-scoped dispatch credentials. If the workflow exposes them as
`SYSTEM_TEST_APP_ID` and `SYSTEM_TEST_APP_PRIVATE_KEY`, they must belong to a dispatch-only App installed solely on
`pipelineframework`, never the coordinator App. The trusted publisher uses the repository `GITHUB_TOKEN` with
`packages: write`; fork publication additionally requires the
`safe-to-system-test` label. Never expose publication, dispatch or status credentials to owner-suite jobs.

Always use an isolated Maven local repository for Maven commands:

```sh
./mvnw <goals> -Dmaven.repo.local="$PWD/.m2/repository"
```

Do not commit, push, publish, or make changes to the TPF monorepo as part of work in this repository unless separately requested.
