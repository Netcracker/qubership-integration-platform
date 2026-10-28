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

The `testing/` bundle is optional and does not enable runtime knowledge. After the build succeeds, create an image
with the existing Dockerfile:

```bash
docker build -f ai-service/Dockerfile -t qip-ai-service:local ai-service
```

The full build also writes `ai-service/target/knowledge-runtime` for the knowledge sidecar.
Use the [Docker Compose setup](../infrastructure/docker/README.md) to run the service and sidecar with the required
configuration. The public documentation sidecar serves both variants; only the full variant needs the private knowledge
mount.
