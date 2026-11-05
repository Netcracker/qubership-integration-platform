# Rocky AI service

Rocky answers platform questions, explains existing chains, manages catalog entries, imports API specifications,
and deploys existing chains.

## Build the service

Run from the repository root with JDK 21 and Maven.

```bash
mvn -f ai-service/pom.xml -Dgpg.skip=true -Dmaven.test.skip=true package
```

The command writes the Quarkus application to `ai-service/target/quarkus-app/`. If Maven cannot resolve
dependencies from GitHub Packages, configure credentials for the `github` server in your Maven settings. The
[repository Maven settings](../.github/maven-settings.xml) read `MAVEN_GITHUB_USERNAME` and
`MAVEN_GITHUB_PASSWORD` when you pass `-s .github/maven-settings.xml`.

Run regression checks separately through the [shared test runner](e2e/test-system.md).

## Build the image

After Maven finishes, build the image from its Quarkus output.

```bash
docker build -f ai-service/Dockerfile -t qip-ai-service:local ai-service
```

The AI service uses the documentation sidecar to search the product manuals. The Compose `ai` profile starts
both services. Follow the [local Rocky quickstart](docs/quickstart.md) to configure the model and try the UI or HTTP API.

For Kubernetes, set the [Helm sidecar readiness probe](../infrastructure/helm/cip-dev/charts/cip-ai-service/templates/cip-ai-service-deployment.yaml)
to `/v1/documentation/manifest`, the same endpoint the Compose probe uses.
