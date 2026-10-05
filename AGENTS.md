# Compiler Repository Instructions

## Development dependency policy

- Development on `main` follows the active TPF snapshot line, currently `26.10.1-SNAPSHOT`; do not pin another TPF component to `26.9.4` or an older snapshot line.
- Maven requires a version: do not use `LATEST`, `RELEASE`, or version ranges to stand in for Git `main`. Applications should select the snapshot product BOM; retained component properties are compatibility-test override points, not separate application version choices.
- `.mvn/maven.config` includes `-U` so cached snapshot metadata is refreshed. Every Maven invocation still needs the isolated local repository required below.
- Maven-producing repositories publish Central snapshots on pushes to `main` and manually for recovery. Nightly full-train testing remains separate; snapshot publication is not scheduled nightly. Publication is asynchronous, not an atomic cross-repository transaction; a green merge alone does not mean publication completed. Check the publisher before retrying dependent builds.
- Coordinated changes use compatibility sets before merge. Those runs continue to use exact immutable candidate/baseline versions, not floating snapshots. No composite source reactor or extra Maven profile is introduced.
- Freeze compatible published coordinates for a stable release. Never alter an already published release, connector contract identity, or pipeline release pin to make development float.

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

## Cross-repository system tests

Owner-local verification is the first gate. `.github/tpf-system-tests.json` owns the stable compiler suite command.
`TPF Candidate Build` and the trusted publisher create an immutable, commit-specific compiler candidate;
`tpf/system-tests` records downstream evidence on that exact source SHA.

For an ordinary single-repository pull request, use the candidate publisher and singleton system-test path above.
For a coordinated change, give every participating pull request the same head-branch name under the same GitHub
owner. Candidate intake discovers those open component pull requests and dispatches one deterministic compatibility
set automatically; later intake events for the same set cancel the older in-flight run. Do **not** wait for
participating candidate publishers and do not merge or publish snapshots one repository at a time.

Use the manual
[`TPF System Tests — Compatibility Set`](https://github.com/The-Pipeline-Framework/pipelineframework/actions/workflows/system-test-compatibility-set.yml)
entry point only when a coordinated set intentionally uses different branch names. Supply one stable set ID and
2–10 pull-request URLs, one per line. Both paths pin each exact PR head and current base, build the participating
Maven reactors and derived downstream closure in dependency order into one isolated repository, and report one
`tpf/system-tests` result to every participating SHA. Do not manually add unchanged downstream repositories or
publish intermediate snapshots; the coordinator derives current-main and downstream closure targets. Do not
substitute snapshots, floating branch heads, source checkouts or a composite Maven reactor. See the canonical
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

Do not introduce Maven profiles except `central-publishing`. It may attach, sign, and deploy the canonical artifact
but must not select another source universe or compilation model.

Do not commit, push, publish, or change another repository unless explicitly requested.
