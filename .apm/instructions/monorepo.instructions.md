---
description: Monorepo layout, top-level build commands, branch model, CI, and the release process.
applyTo: "**/*"
---

### Monorepo nature

Each top-level directory is a former standalone repository, consolidated with its full Git
history. Module guidance ships as APM instructions under `.apm/instructions/`, one file per
module, scoped by `applyTo` to that module's path:

- `engine` — Spring Boot execution engine (Camel runtime, custom components, deployment lifecycle, multi-datasource)
- `micro-engine` — Quarkus variant of the engine
- `runtime-catalog` — chains/elements/deployments catalog, Consul publishing
- `sessions-management` — session storage/search over OpenSearch
- `schemas` — JSON Schema sources + ts-node build/codegen pipeline (npm + Maven publish)
- `vscode-extension` — offline web extension, embeds `@netcracker/qip-ui`
- `infrastructure` — Docker Compose + Helm for the local stack
- `testing-service` — chain test cases and endpoint mocks; the repository's only Go module, built with the Go toolchain rather than Maven or npm
- `e2e` — the end-to-end suite (Playwright) against a local stack, Docker Compose or a Kubernetes cluster (`CIP_TARGET`); outside the npm workspaces, with its own `package.json` and lockfile
- `integration-build-pipeline` — `qip-integration-build-pipeline`, the chain-compilation library runtime-catalog depends on (codegen, parsers, mapper, library, chain, io). It holds the ANTLR mapper grammar, the Handlebars element templates, and the import-file migrations. No instruction of its own yet.
- `integration-build-maven-plugin` — `qip-integration-build-maven-plugin`, the Maven plugin (`build-crs`, `build-libs`) built on integration-build-pipeline. No instruction of its own.
- `ai-service` — `qip-ai-service`, the Quarkus service for AI-assisted chain design; Compose starts it under the `ai` profile, Helm ships it as `cip-ai-service`. No instruction of its own; `ai-service/CONTEXT.md` describes it.

The `ui` module's guidance lives in three `ui-*` skills, loaded on demand because it is long; three thin `ui-*`
instructions of the same names only say when to apply each skill. Two directories carry shared build
configuration instead of a service: `checkstyle/` (the `qip-checkstyle` artifact) and
`parent/` (the Maven parent POM `qip-monorepo-parent`).

Edit these instructions under `.apm/`, never the generated `AGENTS.md` files.

### Top-level build commands

#### Maven aggregator (`pom.xml`, `groupId: org.qubership.integration.platform`, `artifactId: qip-monorepo`)

```bash
mvn clean install -Dgpg.skip=true   # Build all Java modules (see <modules> in the root pom.xml)
mvn -pl engine -am package          # Single module + its dependencies, nothing written to ~/.m2
```

GPG signing binds to the `verify` phase (`parent/pom.xml`, and the POMs of the modules without a parent), so `package`
needs no flag and anything that reaches `verify` or `install` needs `-Dgpg.skip=true`.

Consolidation did not rename any published artifact; each module's POM still carries its original coordinates.

The npm workspaces (`schemas`, `ui`, `vscode-extension`) have their own `npm-workspaces` instruction: the top-level
npm commands, the Node version, and the build order between them.

#### Local stack

The `infrastructure` instruction and `infrastructure/docker/README.md` give the Compose commands, the overlay files,
the profiles, and the ports.

### Branch model

- `main` — every module at HEAD
- `release/0.1` … `release/0.5` — historical release snapshots (renamed from the old `v0.X` tags/branches), each with the module set that existed at release time
- `release/1.0` — the maintenance line of the 1.0 release, which still takes fixes and module releases
- `feature/#173-core-adaptation` (engine), `feature/#109-core-adaptation` (runtime-catalog), `feature/#33` (sessions-management) — single-module feature branches with more private `com.netcracker.cloud` dependencies

`main` needs GitHub Packages credentials too: `parent/pom.xml` imports `com.netcracker.cloud:dbaas-client-bom` and
`maas-client-bom`, which are not on Maven Central. Building requires `~/.m2/settings.xml` with a `github` server whose PAT has
`read:packages` on `maven.pkg.github.com/Netcracker/*`; CI passes `.github/maven-settings.xml`. A 401 or 403 on a
`com.netcracker.cloud` artifact is a credential problem, not a code problem.

### CI workflows

Each module owns a `<module>-build.yaml` / `<module>-release.yaml` pair under `.github/workflows/`, alongside the repo-wide workflows. **Conventional Commits** is enforced on PR titles and commit messages — non-conforming PRs are rejected at the CI gate.

`docs/` is in `.gitignore`; never force-add a plan.

### Release process

A release is version-type driven: you pick `patch`, `minor`, or `major` and never a version
number, and the workflow computes, publishes, tags, and commits the bump back. When cutting a release, running a
release wave, or publishing a snapshot, apply the `release-process` skill.

### Modules used as libraries

Downstream applications consume engine, micro-engine, `@netcracker/qip-ui`, and `@netcracker/qip-vscode-extension` as
libraries and supply their own implementations. An interface with no implementation here (`SdsService`), a bean that
`@ConditionalOnBean` never activates locally, or a webview message type that `apiRouter.ts` does not handle is an
extension point, not dead code. Do not remove it or report it as broken; ask whether a consumer relies on it.

### Legacy `qip.*` property names

Properties use the `cip.` prefix. In engine, runtime-catalog, and sessions-management,
`PropertyPrefixAliasEnvironmentPostProcessor` keeps the old `qip.` names working from every property source, Consul
included. For a lookup of `cip.x`, the first source that holds `cip.x` or `qip.x` wins, and within one source `cip.x`
wins, so a `qip.x` in Consul still overrides a `cip.x` default in `application.yml`. Read and declare new keys under
`cip.` only. Micro-engine has no such alias.

### Cross-module change tips

- A Java change rarely needs the full aggregator build; prefer `mvn -pl <module> -am package`. `install` writes the branch's snapshots to the shared `~/.m2`, where every other checkout and worktree builds against them.
- The `parent/` POM controls Spring Boot, MapStruct, Lombok, Checkstyle, JaCoCo versions for every Spring module — bump dependency versions there, not in module POMs.
- The `qip-checkstyle` rules version is pinned by `qip-checkstyle-revision` in `parent/pom.xml` and again in the POMs that set it themselves: micro-engine, schemas, sessions-management, and ai-service. Bump them together. Engine and micro-engine carry a `checkstyle-suppressions.xml` that excludes only `target/` and applies only to a build started inside the module directory.
- MapStruct runs with `-Amapstruct.unmappedTargetPolicy=IGNORE` (`parent/pom.xml`, and micro-engine's own POM), so do not add a per-field `ignore = true`. On a null source collection, `defaultExpression` and `SET_TO_DEFAULT` change nothing; only `@IterableMapping(nullValueMappingStrategy = RETURN_DEFAULT)` on the collection's own mapping method defaults it, and a Lombok `@Builder.Default` loses to an explicit `builder.field(null)` (#805).
