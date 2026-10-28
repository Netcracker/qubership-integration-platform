# Build the AI service

Build the customer distribution from the repository root with Java 21 and Maven:

```bash
./ai-service/scripts/build.sh -Dmaven.test.skip=true
```

This command assembles runtime artifacts. Run regression and acceptance checks separately through the
[shared test runner](e2e/test-system.md).

The build packages the AI service. If neither `.private/rocky-skills` nor `.private/rocky-knowledge` exists,
Maven builds the base service. If either path exists, Maven enables `with-rocky-skills`; the existing package
validator checks the supplied packages and includes runtime knowledge.
Partial, corrupt, or incomplete packages fail the build with validation errors. The build never falls back to
the base service after a package validation failure. Without Rocky packages, CREATE and EDIT are disabled.

The `testing/` bundle is optional and does not enable runtime knowledge. After the build succeeds, create an image
with the existing Dockerfile:

```bash
docker build -f ai-service/Dockerfile -t qip-ai-service:local ai-service
```

The full build also writes `ai-service/target/knowledge-runtime` for the knowledge sidecar.
Use the [Docker Compose setup](../infrastructure/docker/README.md) to run the service and sidecar with the required
configuration. The public documentation sidecar serves both variants; only the full variant needs the private knowledge
mount.
