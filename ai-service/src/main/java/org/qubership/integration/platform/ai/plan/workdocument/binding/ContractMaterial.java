package org.qubership.integration.platform.ai.plan.workdocument.binding;

import java.util.List;

/**
 * Schema load for one pinned operation. A missing or failed read stays a failed result. It is not
 * an empty schema.
 */
public sealed interface ContractMaterial
    permits ContractMaterial.Ready,
        ContractMaterial.MissingSchema,
        ContractMaterial.Unavailable,
        ContractMaterial.ReadFailed,
        ContractMaterial.Incompatible {

  String contractReference();

  String operationId();

  String version();

  record Ready(
      String contractReference, String operationId, String version, List<PortSchemaMaterial> ports)
      implements ContractMaterial {

    public Ready {
      ports = List.copyOf(ports);
    }
  }

  /** The pinned operation has no schema for this port. */
  record MissingSchema(
      String contractReference, String operationId, String version, String port, String reason)
      implements ContractMaterial {}

  /** No pinned catalog source can supply schemas for this contract. */
  record Unavailable(
      String contractReference, String operationId, String version, String reason)
      implements ContractMaterial {}

  /** The catalog read failed. The failure text is the catalog error. */
  record ReadFailed(
      String contractReference, String operationId, String version, String reason)
      implements ContractMaterial {}

  /** The catalog operation or version is not the pinned selection. */
  record Incompatible(
      String contractReference, String operationId, String version, String reason)
      implements ContractMaterial {}
}
