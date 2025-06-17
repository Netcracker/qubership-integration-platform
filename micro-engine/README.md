# Qubership Integration Platform — Engine

Engine service is a part of Qubership Integration Platform.

This service:
- Creates context for integration flows (so-called integration chains) using configuration provided by [Design-Time Catalog](https://github.com/Netcracker/qubership-integration-designtime-catalog), [Runtime Catalog](https://github.com/Netcracker/qubership-integration-runtime-catalog), and [Variables Management](https://github.com/Netcracker/qubership-integration-variables-management) services.
- Manages registration of integration chains' endpoints on control plane.
- Runs integration chains.
- Records sessions of integration chains' execution. These records can be later accessed via [Sessions Management](https://github.com/Netcracker/qubership-integration-sessions-management) service.
- Collects various metrics of integration chains execution.

Engine service uses [Apache Camel](https://camel.apache.org/) for defining and execution of integration logic.

Engine service publishes integration chains' deployment state to Consul.

To store recorded sessions of integrated chains' execution, the service uses OpenSearch.
It creates if not exists index in OpenSearch and sets up index rotation policy via [ISM](https://docs.opensearch.org/docs/latest/im-plugin/ism/index/).

## Installation

Variables Management Service is a Spring Boot Application and requires Java 21 and Maven to build.
[Dockerfile](Dockerfile) is provided to build a containerized application.
It can be run locally using a [docker compose configuration](https://github.com/Netcracker/qubership-integration-platform).

## Configuration

Application parameters can be set by environment variables.

| Environment variable                | Default value                                        | Description                                                                                                                  |
|-------------------------------------|------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| ROOT_LOG_LEVEL                      | INFO                                                 | Logging level                                                                                                                |
| CONSUL_URL                          | `http://consul:8500`                                 | Consul URL                                                                                                                   |
| CONSUL_ADMIN_TOKEN                  |                                                      | Consul assess token                                                                                                          |
| KUBE_TOKEN_PATH                     | /var/run/secrets/kubernetes.io/serviceaccount/token  | Kubernetes token path                                                                                                        |
| KUBE_CERT_PATH                      | /var/run/secrets/kubernetes.io/serviceaccount/ca.crt | Kubernetes certificate path                                                                                                  |
| MICROSERVICE_NAME                   |                                                      | Microservice name.                                                                                                           |
| DEPLOYMENT_VERSION                  | v1                                                   | Deployment version for bluegreen.                                                                                            |
| NAMESPACE                           |                                                      | Kubernetes namespace.                                                                                                        |
| ORIGIN_NAMESPACE                    |                                                      | Origin namespace for bluegreen.                                                                                              |
| TRACING_ENABLED                     | false                                                | If true, enables application tracing via OpenTelemetry protocol.                                                             |
| TRACING_HOST                        |                                                      | Tracing endpoint URL.                                                                                                        |
| TRACING_SAMPLER_PROBABILISTIC       | 0.01                                                 | Tracing sampling probability. By default, application samples only 1% of requests to prevent overwhelming the trace backend. |
| POSTGRES_URL                        | postgres:5432/postgres                               | Database URL                                                                                                                 |
| POSTGRES_USER                       | postgres                                             | Database user                                                                                                                |
| POSTGRES_PASSWORD                   | postgres                                             | Database password                                                                                                            |
| PG_MAX_POOL_SIZE                    | 30                                                   | The maximum number of connections that can be held in the connection pool.                                                   |
| PG_MIN_IDLE                         | 0                                                    |                                                                                                                              |
| PG_IDLE_TIMEOUT                     | 300000                                               | Sets the maximum allowed idle time between queries, when not in a transaction.                                               |
| PG_LEAK_DETECTION_INTERVAL          | 30000                                                | The maximum number of milliseconds that a client will wait for a connection from the pool.                                   |
| OPENSEARCH_HOST                     | opensearch                                           | OpenSearch hostname                                                                                                          |
| OPENSEARCH_PORT                     | 9200                                                 | OpenSearch port                                                                                                              |
| OPENSEARCH_PROTOCOL                 | http                                                 | OpenSearch service protocol                                                                                                  |
| OPENSEARCH_USERNAME                 |                                                      | OpenSearch username                                                                                                          |
| OPENSEARCH_PASSWORD                 |                                                      | OpenSearch password                                                                                                          |
| OPENSEARCH_PREFIX                   |                                                      | A prefix string that is if not empty added followed by underscore to the OpenSearch index name.                              |
| OPENSEARCH_CONNECTION_TIMEOUT       | 5000                                                 | OpenSearch client connection timeout, ms.                                                                                    |
| OPENSEARCH_INDEX_SHARDS             | 3                                                    | OpenSearch index shards count                                                                                                |
| OPENSEARCH_ROLLOVER_MIN_INDEX_SIZE  |                                                      | Minimal index size to rollover. Uneset by default.                                                                           |
| MONITORING_ENABLED                  | false                                                |                                                                                                                              |
| IDEMPOTENCY_ENABLED                 | false                                                | Enables idempotency support on triggers. Requires Redis service.                                                             |
| REDIS_HOST                          | redis                                                | Redis host                                                                                                                   |
| REDIS_PORT                          | 6379                                                 | Redis port                                                                                                                   |
| REDIS_USER                          |                                                      | Redis username                                                                                                               |
| REDIS_PASSWORD                      |                                                      | Redis password                                                                                                               |
| CAMEL_KAFKA_PREDEPLOY_CHECK_ENABLED | true                                                 | Enables predeploy check for Kafka elements.                                                                                  |
| CAMEL_AMQP_PREDEPLOY_CHECK_ENABLED  | true                                                 | Enables predeploy check for AMQP elements.                                                                                   |
| RUNTIME_CATALOG_SERVICE_URL         | `http://runtime-catalog:8080`                        | Runtime Catalog Service URL.                                                                                                 |


Configuration can be overridden with values stored in Consul.
The ```config/${NAMESPACE}``` prefix is used.

Application has 'development' Spring profile to run service locally with minimum dependencies.

## Dependencies

This service relies on [Design-Time Catalog](https://github.com/Netcracker/qubership-integration-designtime-catalog), [Runtime Catalog](https://github.com/Netcracker/qubership-integration-runtime-catalog), and [Variables Management](https://github.com/Netcracker/qubership-integration-variables-management) services.
It also requires:
- Consul
- OpenSearch
- PostgreSQL
- Redis (if idempotency support enabled).

## Contribution

For the details on contribution, see [Contribution Guide](CONTRIBUTING.md). For details on reporting of security issues see [Security Reporting Process](SECURITY.md).

The library uses [Checkstyle](https://checkstyle.org/) via [Maven Checkstyle Plugin](https://maven.apache.org/plugins/maven-checkstyle-plugin/) to ensure code style consistency among Qubership Integration Platform's libraries and services. The rules are located in a separate [repository](https://github.com/Netcracker/qubership-integration-checkstyle).

Commits and pool requests should follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) strategy.

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in [LICENSE](LICENSE) file.

## Additional Resources

- [Qubership Integration Platform](https://github.com/Netcracker/qubership-integration-platform) — сore deployment guide.
