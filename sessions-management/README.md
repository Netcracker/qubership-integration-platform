# Qubership Integration Platform — Sessions Management

Sessions Management Service is a part of Qubership Integration Platform.
It provides an API to manage recorded sessions of integration flows' execution stored in OpenSearch index.
This service is designed for debugging, monitoring, and analytical purpose and allows full text search in sessions along with complex session filtering.

## Installation

Sessions Management Service is a Spring Boot Application and requires Java 21 and Maven to build.
[Dockerfile](Dockerfile) is provided to build a containerized application.
It can be run locally using a [docker compose configuration](https://github.com/Netcracker/qubership-integration-platform).

## Configuration

Application parameters can be set by environment variables.

| Environment variable                    | Default value        | Description                                                                                                                  |
|-----------------------------------------|----------------------|------------------------------------------------------------------------------------------------------------------------------|
| ROOT_LOG_LEVEL                          | INFO                 | Logging level                                                                                                                |
| CONSUL_URL                              | `http://consul:8500` | Consul URL                                                                                                                   |
| CONSUL_ADMIN_TOKEN                      |                      | Consul assess token                                                                                                          |
| MAX_UPLOAD_MULTIPART_FILE_SIZE          | 25                   | Maximum file size to upload, MB. Limits data size for upload operations like sessions import.                                |
| MICROSERVICE_NAME                       |                      | Microservice name.                                                                                                           |
| DEPLOYMENT_VERSION                      | v1                   | Deployment version for bluegreen.                                                                                            |
| NAMESPACE                               |                      | Kubernetes namespace.                                                                                                        |
| ORIGIN_NAMESPACE                        |                      | Origin namespace for bluegreen.                                                                                              |
| TRACING_ENABLED                         | false                | If true, enables application tracing via OpenTelemetry protocol.                                                             |
| TRACING_HOST                            |                      | Tracing endpoint URL.                                                                                                        |
| TRACING_SAMPLER_PROBABILISTIC           | 0.01                 | Tracing sampling probability. By default, application samples only 1% of requests to prevent overwhelming the trace backend. |
| OPENSEARCH_HOST                         | opensearch           | OpenSearch hostname                                                                                                          |
| OPENSEARCH_PORT                         | 9200                 | OpenSearch port                                                                                                              |
| OPENSEARCH_PROTOCOL                     | http                 | OpenSearch service protocol                                                                                                  |
| OPENSEARCH_USERNAME                     |                      | OpenSearch username                                                                                                          |
| OPENSEARCH_PASSWORD                     |                      | OpenSearch password                                                                                                          |
| OPENSEARCH_PREFIX                       |                      | A prefix string that is if not empty added followed by underscore to the OpenSearch index name.                              |
| OPENSEARCH_SESSION_DEFAULT_BUFFER_LIMIT | 104857600            | Buffer limit for the OpenSearch response concumer, bytes.                                                                    |

Configuration can be overridden with values stored in Consul.
The ```config/${NAMESPACE}``` prefix is used.

Application has 'development' Spring profile to run service locally with minimum dependencies.

## Dependencies

This service relies on [Design-Time Catalog Service](https://github.com/Netcracker/qubership-integration-designtime-catalog), which is used to provide integration chain names.
It also requires Consul and OpenSearch services.

## Contribution

For the details on contribution, see [Contribution Guide](CONTRIBUTING.md). For details on reporting of security issues see [Security Reporting Process](SECURITY.md).

The library uses [Checkstyle](https://checkstyle.org/) via [Maven Checkstyle Plugin](https://maven.apache.org/plugins/maven-checkstyle-plugin/) to ensure code style consistency among Qubership Integration Platform's libraries and services. The rules are located in a separate [repository](https://github.com/Netcracker/qubership-integration-checkstyle).

Commits and pool requests should follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) strategy.

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in [LICENSE](LICENSE) file.

## Additional Resources

- [Qubership Integration Platform](https://github.com/Netcracker/qubership-integration-platform) — core deployment guide.
