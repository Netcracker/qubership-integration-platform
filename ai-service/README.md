# Build the AI service

Build the customer distribution from the repository root with Java 21 and Maven:

```bash
./ai-service/scripts/build.sh -Dmaven.test.skip=true
```

This command assembles runtime artifacts. Run regression and acceptance checks separately through the
[shared test runner](e2e/test-system.md).

The build packages the AI service. For customer builds, place both runtime packages under
`integration-platform-skills/rocky-skills/` and `integration-platform-skills/rocky-knowledge/`. If either
customer runtime path exists, the build selects that pair and passes both roots to Maven; the existing package
validator rejects missing, partial, or corrupt packages. It does not mix packages from different layouts.

For developer builds, `.private/rocky-skills/` and `.private/rocky-knowledge/` remain supported. The private
pair is selected when no customer runtime path exists and either private path exists. An `integration-platform-skills/`
folder containing only APM source files does not select runtime packages. If the outer folder exists without
runtime package paths and no private runtime package exists, the build fails explicitly. With no runtime packages
at either location, Maven builds the base service. Package validation failures never fall back to the base service.
Without Rocky runtime packages, CREATE and EDIT are disabled.

## Skills and chain tests

Keep the private runtime packages and the separate testing archive under this delivery directory:

```text
integration-platform-skills/
├── rocky-skills/
│   ├── manifest.json
│   ├── skills/                 # Runtime instructions for CREATE and EDIT
│   ├── runtime/                # Runtime contracts and product pipelines
│   │   └── examples/           # Service-specific examples, grouped by skill ID
│   └── testing/                # Extracted optional testing archive
│       ├── README.md           # Test bundle setup and archive instructions
│       ├── suites.json
│       ├── skills/             # Skills for the development/testing agent
│       ├── element-prompts/    # Original manual generation requests and guide
│       └── product-pipeline/   # CREATE and COMPARE_AND_PATCH scenarios
└── rocky-knowledge/             # Certified knowledge package for the sidecar
```

The developer installation uses the same package layout under `.private/`. Runtime skills and knowledge are
supplied separately from this public repository. Service unit tests and their small mechanism fixtures stay in
`ai-service/src/test/`; live chain scenarios, planner/executor inputs, generator goldens, and test-agent skills
belong to `rocky-skills/testing/`.

Runtime examples belong under `rocky-skills/runtime/examples/<skill-id>/`. The build records those JSON files
in the standalone runtime index. The service includes only the selected skill's examples in its context,
including script-repair turns. This does not require an addon document or restore addon prompt overlays.
Files named `invalid-*.json` are presented as counterexamples that must be rejected. Examples illustrate contracts;
they do not add author requirements or override the current capture-tool schema, ownership, or approval rules.
Package maintainers must update the source manifest checksums after changing runtime example files.

Extract the testing archive at the `rocky-skills/` root; the archive already contains its `testing/` directory.
Its `skillsPackageVersion` must match the runtime `manifest.json` version. Use Python 3.12 or later, Bash, jq,
and ripgrep. Install PyYAML and the evaluator development dependencies listed in the bundle
`evaluator/pyproject.toml` into the Python environment used by the runner. Java service profiles also need Java 21
and Maven. From the repository root, inspect and run the installed offline profiles:

```bash
python3 ai-service/e2e/run.py --package integration-platform-skills/rocky-skills catalog
python3 ai-service/e2e/run.py --package integration-platform-skills/rocky-skills run chain-offline
python3 ai-service/e2e/run.py --package integration-platform-skills/rocky-skills serve
```

The local results UI is available at [localhost:8110](http://127.0.0.1:8110). The runner defaults to
`.private/rocky-skills`; use `--package` for the delivery layout above. The `chain-acceptance` profile includes both
CREATE and COMPARE_AND_PATCH scenarios. Live profiles require a configured running service and explicit
`--allow-llm`; follow the [shared test runner guide](e2e/test-system.md) for service gates, provider settings,
reports, and cleanup. Read the installed `testing/README.md` for direct runners and building the separate archive.

For manual generation, read `testing/element-prompts/README.md` and submit a selected request, such as
`http-sender.md`, in a fresh product chat. These requests are inputs, not registered automated tests or passing
results. The guide explains unique names, environment-specific bindings, and verification.

Load `testing/skills/cip-tool-chain-eval/SKILL.md` into the development agent when running chain tests. Service
mechanism tests use [cip-tool-ai-service-tests](e2e/skills/cip-tool-ai-service-tests/SKILL.md). These testing skills
stay outside the generation agent's runtime context. Maven includes runtime skill documents and contracts, while
`testing/` is excluded from runtime resources and does not enable CREATE or EDIT.

## Build the image

The `testing/` bundle is optional and does not enable runtime knowledge. After the build succeeds, create an image
with the existing Dockerfile:

```bash
docker build -f ai-service/Dockerfile -t qip-ai-service:local ai-service
```

The full build also writes `ai-service/target/knowledge-runtime` for the knowledge sidecar.
Use the [Docker Compose setup](../infrastructure/docker/README.md) to run the service and sidecar with the required
configuration. The public documentation sidecar serves both variants; only the full variant needs the private knowledge
mount.
