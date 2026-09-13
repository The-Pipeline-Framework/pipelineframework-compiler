# Repository instructions

This repository builds the standalone `pipelineframework-compiler` artifact. Keep compiler implementation, JSR-269 service registration, and compiler tests here. Shared API, semantic model, DSL, runtime-core, runtime protocol, and representation-provider contracts are dependencies; runtime and deployment implementations do not belong in this repository.

Always use an isolated Maven local repository for Maven commands:

```sh
./mvnw <goals> -Dmaven.repo.local="$PWD/.m2/repository"
```

Do not commit, push, publish, or make changes to the TPF monorepo as part of work in this repository unless separately requested.
