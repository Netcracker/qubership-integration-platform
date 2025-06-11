# Qubership Integration Platform — Variables Management

Variables Management Service is a part of Qubership Integration Platform.
It provides an API for operations on variables within the integration platform.
These variables are designed to store parts of solution configuration that is needed to be provided to the integration logic.

Service defines two kinds of variables: common and secured.

Common variables are stored in Consul and should not be used to store any sensitive data.

Secured variables are designed to store sensitive data like passwords, tokens, etc. and stored in a cloud as secrets.
An arbitrary number of secrets can be used to store secured variables.
By default ```qip-secured-variables-v2``` secret is used.
Label ```qip-variable-type``` is added to secrets that store Integration Platform variables.

All operations on variables are being logged, and an API is provided to get the action journal.
Variables Management service uses PostgreSQL database to store action log.

## Installation

Variables Management Service is a Spring Boot Application and requires Java 21 and Maven to build.
[Dockerfile](Dockerfile) is provided to build a containerized application.
It can be run locally using a [docker compose configuration](https://github.com/Netcracker/qubership-integration-platform).

## Configuration

Application parameters can be set by environment variables.

| Environment variable           | Default value                                        | Description                                                                                                                            |
|--------------------------------|------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| TOMCAT_PORT                    | 8080                                                 | Port to listen                                                                                                                         |
| ROOT_LOG_LEVEL                 | INFO                                                 | Logging level                                                                                                                          |
| CONSUL_URL                     | `http://consul:8500/`                                | Consul URL                                                                                                                             |
| CONSUL_ADMIN_TOKEN             |                                                      | Consul assess token                                                                                                                    |
| ACTION_LOG_CLEANUP_INTERVAL    | 14 days                                              | Maximum age of action log records. Records older than specified value will be deleted. Examples: '1 hour', '7 days', '2 years 3 month' |
| ACTION_LOG_CLEANUP_CRON        | 0 0 0 ? * SAT                                        | Action log cleanup task schedule in cron expression format                                                                             |
| MAX_UPLOAD_MULTIPART_FILE_SIZE | 25                                                   | Maximum file size to upload, MB. Limits data size for upload operations like variables import.                                         |
| KUBE_TOKEN_PATH                | /var/run/secrets/kubernetes.io/serviceaccount/token  | Kubernetes token path                                                                                                                  |
| KUBE_CERT_PATH                 | /var/run/secrets/kubernetes.io/serviceaccount/ca.crt | Kubernetes certificate path                                                                                                            |
| MICROSERVICE_NAME              |                                                      | Microservice name.                                                                                                                     |
| DEPLOYMENT_VERSION             | v1                                                   | Deployment version for bluegreen.                                                                                                      |
| NAMESPACE                      |                                                      | Kubernetes namespace.                                                                                                                  |
| ORIGIN_NAMESPACE               |                                                      | Origin namespace for bluegreen.                                                                                                        |
| TRACING_ENABLED                | false                                                | If true, enables application tracing via OpenTelemetry protocol.                                                                       |
| TRACING_HOST                   |                                                      | Tracing endpoint URL.                                                                                                                  |
| TRACING_SAMPLER_PROBABILISTIC  | 0.01                                                 | Tracing sampling probability. By default, application samples only 1% of requests to prevent overwhelming the trace backend.           |
| POSTGRES_URL                   | postgres:5432/postgres                               | Database URL                                                                                                                           |
| POSTGRES_USER                  | postgres                                             | Database user                                                                                                                          |
| POSTGRES_PASSWORD              | postgres                                             | Database password                                                                                                                      |
| PG_MAX_POOL_SIZE               | 30                                                   | The maximum number of connections that can be held in the connection pool.                                                             |
| PG_MIN_IDLE                    | 0                                                    |                                                                                                                                        |
| PG_IDLE_TIMEOUT                | 300000                                               | Sets the maximum allowed idle time between queries, when not in a transaction.                                                         |
| PG_LEAK_DETECTION_INTERVAL     | 30000                                                | The maximum number of milliseconds that a client will wait for a connection from the pool.                                             |

Configuration can be overridden with values stored in Consul.
The ```config/${NAMESPACE}``` prefix is used.

Application has 'development' Spring profile to run service locally with minimum dependencies.

## Dependencies

- Consul service.
- Access to Kubernetes API.
- PostgreSQL database.

## Contribution

For the details on contribution, see [Contribution Guide](CONTRIBUTING.md). For details on reporting of security issues see [Security Reporting Process](SECURITY.md).

The library uses [Checkstyle](https://checkstyle.org/) via [Maven Checkstyle Plugin](https://maven.apache.org/plugins/maven-checkstyle-plugin/) to ensure code style consistency among Qubership Integration Platform's libraries and services. The rules are located in a separate [repository](https://github.com/Netcracker/qubership-integration-checkstyle).

Commits and pool requests should follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) strategy.

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in [LICENSE](LICENSE) file.

## Additional Resources

- [Qubership Integration Platform](https://github.com/Netcracker/qubership-integration-platform) — сore deployment guide.
