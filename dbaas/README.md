# qip-dbaas

The `qip-dbaas` chart declares the databases that QIP services use and the access other services get to them. The
[DBaaS Operator](https://github.com/Netcracker/qubership-dbaas/blob/main/dbaas-operator/docs/DBaaS%20Operator.md)
reconciles these resources and forwards them to dbaas-aggregator, which creates the databases and grants the roles.

The resources use the operator's native CRDs in the `dbaas.netcracker.com/v1` group. They replace the
`core.netcracker.com/v1` `kind: DBaaS` declarations that the Core Operator processed. The
[migration guide](https://github.com/Netcracker/qubership-dbaas/blob/main/dbaas-operator/docs/howto/migrate-declarations-from-core-operator.md)
maps the old fields to the new ones.

## Resources

The Helm templates render six resources:

| Template | Kind | Database |
| --- | --- | --- |
| `catalog-internaldatabase.yaml` | `InternalDatabase` | runtime-catalog `configs`, PostgreSQL. On first creation it is cloned from the database whose classifier carries `logicalDbMarker`. |
| `catalog-databaseaccesspolicy.yaml` | `DatabaseAccessPolicy` | Grants `admin` on the runtime-catalog databases to the legacy systems-catalog service |
| `engine-configs-internaldatabase.yaml` | `InternalDatabase` | engine `configs`, PostgreSQL |
| `engine-checkpoints-internaldatabase.yaml` | `InternalDatabase` | engine checkpoints, PostgreSQL |
| `engine-sessions-internaldatabase.yaml` | `InternalDatabase` | engine `sessions`, OpenSearch |
| `engine-databaseaccesspolicy.yaml` | `DatabaseAccessPolicy` | Grants roles on the engine OpenSearch database to sessions-management and the streaming platform |

Every database is declared with `scope: tenant`. The metadata names keep the `-dbdeclaration` and `-dbpolicy` suffixes
of the Core Operator resources.

## Values

| Value | Default | Purpose |
| --- | --- | --- |
| `NAMESPACE` | none | Namespace the resources are created in. Required. |
| `API_DBAAS_ADDRESS` | `http://dbaas-aggregator.dbaas:8080` | Address of dbaas-aggregator. The second segment of its host is the namespace of the DBaaS Operator, written to `spec.operatorNamespace`. |
| `CATALOG_SERVICE_NAME` | `qip-runtime-catalog` | Microservice that owns the runtime-catalog database. |
| `ENGINE_SERVICE_NAME` | `qip-engine` | Microservice that owns the engine databases. |
| `SESSIONS_MANAGEMENT_SERVICE_NAME` | `qip-sessions-management` | Service granted `admin` on the engine OpenSearch database. |
| `STREAMING_PLATFORM_SERVICE_NAME` | empty | Service granted `admin` and `ism` on the engine OpenSearch database. Leave it empty to omit the grant. |
| `SYSTEMS_CATALOG_SERVICE_NAME` | empty | Legacy systems-catalog service granted `admin` on the runtime-catalog databases. Leave it empty to omit the grant. |

The host of `API_DBAAS_ADDRESS` must have the form `<service>.<namespace>`, optionally followed by more domain segments.
The operator skips a resource whose `spec.operatorNamespace` differs from the namespace it runs in, and it writes no
status to that resource.

## Render the chart

Render the Helm templates and check the output before you install:

```bash
helm template qip-dbaas dbaas --set NAMESPACE=<namespace> --set API_DBAAS_ADDRESS=<aggregator-address>
```

## Check the result

After the deployer applies the chart, list the resources:

```bash
kubectl get dbidb,dbdap -n <namespace>
```

Each resource reaches phase `Succeeded` with `READY` set to `True`. Phase `InvalidConfiguration` means the spec has an
error and the operator does not retry; read the message in `status.conditions`. The
[status reference](https://github.com/Netcracker/qubership-dbaas/blob/main/dbaas-operator/docs/DBaaS%20Operator.md#common-status-model)
lists every phase and reason.

Some fields cannot change after a resource is created. On every resource that is `spec.operatorNamespace`; an
`InternalDatabase` adds `spec.classifier` and `spec.type`, and a `DatabaseAccessPolicy` adds `spec.microserviceName`.
To change one, delete the resource and create it again.
