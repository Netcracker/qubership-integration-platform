# Contribution Guide

We'd love to accept patches and contributions to this project.
Please, follow these guidelines to make the contribution process easy and effective for everyone involved.

## Contributor License Agreement

You must sign the [Contributor License Agreement](https://github.com/Netcracker/qubership-workflow-hub/blob/main/CLA/cla.md) in order to contribute.

## Code of Conduct

Please make sure to read and follow the [Code of Conduct](CODE-OF-CONDUCT.md).

## Development checks

Pull request builds and the main test suites run on Ubuntu. The runners use the module's toolchain
directly. Tests that need a deployed stack use Docker Compose or kind on Ubuntu.

Use the commands below to reproduce checks from a fresh checkout. Run repository-level commands
from the root. For a failure in CI, use the arguments and working directory shown in the job log.
Use your Maven and npm registry settings for dependencies that require access.

| Modules | Local entry point |
| --- | --- |
| Engine, Micro Engine, Runtime Catalog, Sessions Management, Integration Build Pipeline, Integration Build Maven Plugin, Checkstyle, Maven parents, and BOM | [Maven checks](#maven-checks) |
| Schemas | [Maven checks](#maven-checks) and [npm checks](#npm-checks) |
| UI and VS Code extension | [npm checks](#npm-checks) |
| Testing Service | [Go checks](#go-checks) |
| AI Service | [Maven checks](#maven-checks) and [AI Service checks](#ai-service-checks) |
| Infrastructure and the end-to-end suite | [Stack and package checks](#stack-and-package-checks) |

### Maven checks

Use JDK 21 and Maven. Replace `engine` with the module directory. Use `parents` or `bom` for the
shared Maven artifacts. The `-am` flag builds reactor dependencies. When changing a shared POM,
also run the check for its affected consumers.

```bash
mvn --batch-mode verify -pl engine -am -Dgpg.skip=true
mvn --batch-mode verify -Dgpg.skip=true
```

The second command checks the whole Maven reactor. For the schemas Maven job, CI also runs
`mvn --batch-mode verify -Dgpg.skip=true` from `schemas/`. The shared Maven workflow uses
[the repository's CI Maven settings](.github/maven-settings.xml) and records any module-specific
flags in the job log.

### npm checks

Use Node.js 22.19 or later in the 22.x line. Install from the root once. The UI uses built schemas,
and the extension's `prepare-deps` script builds the schemas and UI library it embeds.

```bash
npm ci
npm -w @netcracker/qip-schemas run build
npm -w @netcracker/qip-schemas test
npm -w @netcracker/qip-ui run lint
npm -w @netcracker/qip-ui run check-types
npm -w @netcracker/qip-ui test -- --coverage
npm -w @netcracker/qip-ui run test:scripts
npm -w @netcracker/qip-ui run build
```

Run the extension checks from `vscode-extension/` after the root install. The integration tests
start the VS Code web host in Chromium.

```bash
npm run prepare-deps
npx --no-install eslint src
npm run check-types
npm run test:unit:coverage
npm run test:integration
```

### Go checks

Use Go 1.22 as pinned in `testing-service/go.mod`. Run the dependency check from the root.
Run the Go commands from `testing-service/`. The integration tests need Docker with Linux
containers for PostgreSQL.

```bash
bash scripts/check-go-dependencies.sh testing-service
cd testing-service
go build ./...
go vet ./...
go test ./...
go vet -tags integration ./...
go test -tags integration ./...
```

The lint job uses golangci-lint at the version in the
[Testing Service workflow](.github/workflows/testing-service-build.yaml).

### AI Service checks

Use Python 3.12, JDK 21, and Maven. This check runs the schema preparation step in a temporary
project, including paths with spaces. Use the same command on Linux, Windows, and macOS.

```bash
python -m unittest discover -s ai-service/e2e/test_system/tests -p test_schema_build.py -v
```

See the [AI Service workflow](.github/workflows/ai-service-build.yaml) for the offline runner,
evaluator, and documentation sidecar checks.

### Stack and package checks

Install dependencies in `e2e/`. Use the target-specific npm command in PowerShell, Git Bash, WSL, or
a Linux or macOS shell. The schema and harness projects run without a deployment.

```bash
cd e2e
npm ci
npm run check-types
npm run test:compose -- --project=schema --project=harness
npm run test:k8s -- --project=schema --project=harness
npm run build
npm run test:package
```

For product tests, use a dedicated test stack. See the [end-to-end run guide](e2e/README.md) and
the [Kubernetes setup](e2e/k8s/README.md). On Windows, use Git Bash for Bash commands or install
the Linux toolchain inside WSL 2. Keep a WSL checkout and its dependencies in the Linux filesystem.
On Windows and macOS, Docker Desktop must run Linux containers.

### Native host compatibility

Use a focused native check when a supported development scenario depends on the host operating
system. Two workflows check these scenarios on Windows and macOS:

- [AI Schema Host Compatibility](.github/workflows/ai-schema-host-compatibility.yaml) runs
  schema preparation for changes to its script, Maven configuration, or test.
- [End-to-End Host Compatibility](.github/workflows/e2e-host-compatibility.yaml) installs the
  tarball in a temporary consumer project and checks fixture bytes, paths, Bash, processes, and
  build locks. To run it locally from `e2e/`, use `npm run build` and
  `npm run test:package -- --host-smoke`.

Their PR path filters target the files that can affect each scenario. Use **Run workflow** for a
manual check after the workflow is on the default branch. Keep a path-filtered job out of the
repository-wide required check list. A workflow with a required status must report a result
for every PR to which the rule applies.
