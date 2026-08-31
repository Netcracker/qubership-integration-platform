package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/**
 * Typed semantic node. Catalog occurrence id is {@link ServiceCall#serviceCallId()} or {@link
 * Trigger#interactionId()}: the RequirementFlow interaction, not the graph {@code nodeId} and not a
 * fact id.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind",
    visible = true)
@JsonSubTypes({
  @JsonSubTypes.Type(value = SemanticNode.Trigger.class, name = "TRIGGER"),
  @JsonSubTypes.Type(value = SemanticNode.ServiceCall.class, name = "SERVICE_CALL"),
  @JsonSubTypes.Type(value = SemanticNode.Operation.class, name = "OPERATION")
})
public sealed interface SemanticNode
    permits SemanticNode.Trigger, SemanticNode.ServiceCall, SemanticNode.Operation {

  String nodeId();

  SemanticNodeKind kind();

  SemanticProvenance provenance();

  record Trigger(
      String nodeId,
      SemanticNodeKind kind,
      String interactionId,
      String capabilityKey,
      SemanticProvenance provenance)
      implements SemanticNode {

    public Trigger {
      nodeId = DesignArtifacts.requireText(nodeId, "nodeId");
      if (kind != SemanticNodeKind.TRIGGER) {
        throw new IllegalArgumentException("Trigger kind must be TRIGGER");
      }
      interactionId =
          interactionId == null || interactionId.isBlank()
              ? nodeId
              : DesignArtifacts.requireText(interactionId, "interactionId");
      capabilityKey = DesignArtifacts.requireText(capabilityKey, "capabilityKey");
      provenance = provenance == null ? new SemanticProvenance(List.of()) : provenance;
    }

    public Trigger(String nodeId, String capabilityKey, SemanticProvenance provenance) {
      this(nodeId, SemanticNodeKind.TRIGGER, nodeId, capabilityKey, provenance);
    }

    public Trigger(
        String nodeId, String interactionId, String capabilityKey, SemanticProvenance provenance) {
      this(nodeId, SemanticNodeKind.TRIGGER, interactionId, capabilityKey, provenance);
    }
  }

  record ServiceCall(
      String nodeId,
      SemanticNodeKind kind,
      String serviceCallId,
      String operation,
      SemanticProvenance provenance)
      implements SemanticNode {

    public ServiceCall {
      nodeId = DesignArtifacts.requireText(nodeId, "nodeId");
      if (kind != SemanticNodeKind.SERVICE_CALL) {
        throw new IllegalArgumentException("ServiceCall kind must be SERVICE_CALL");
      }
      serviceCallId = DesignArtifacts.requireText(serviceCallId, "serviceCallId");
      operation = DesignArtifacts.requireText(operation, "operation");
      provenance = provenance == null ? new SemanticProvenance(List.of()) : provenance;
    }

    public ServiceCall(
        String nodeId, String serviceCallId, String operation, SemanticProvenance provenance) {
      this(nodeId, SemanticNodeKind.SERVICE_CALL, serviceCallId, operation, provenance);
    }
  }

  record Operation(
      String nodeId, SemanticNodeKind kind, String elementType, SemanticProvenance provenance)
      implements SemanticNode {

    public Operation {
      nodeId = DesignArtifacts.requireText(nodeId, "nodeId");
      if (kind != SemanticNodeKind.OPERATION) {
        throw new IllegalArgumentException("Operation kind must be OPERATION");
      }
      elementType = DesignArtifacts.requireText(elementType, "elementType");
      provenance = provenance == null ? new SemanticProvenance(List.of()) : provenance;
    }

    public Operation(String nodeId, String elementType, SemanticProvenance provenance) {
      this(nodeId, SemanticNodeKind.OPERATION, elementType, provenance);
    }
  }
}
