package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Maps plan graph ids to catalog element ids after skeleton materialization.
 *
 * <p>{@code nodeIdToElementId} is the node owner map. Semantic compiler node ids are graph node
 * ids, so this map is not duplicated under a second node-id field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MaterializationMap(
    String chainId,
    Map<String, String> nodeIdToElementId,
    Map<String, String> semanticEdgeOwnerElementIds,
    Map<String, String> mappingIntentExecutionNodeIds) {

  public MaterializationMap {
    nodeIdToElementId = copy(nodeIdToElementId);
    semanticEdgeOwnerElementIds = copy(semanticEdgeOwnerElementIds);
    mappingIntentExecutionNodeIds = copy(mappingIntentExecutionNodeIds);
  }

  public MaterializationMap withNodeIdToElementId(Map<String, String> nodeIdToElementId) {
    return new MaterializationMap(
        chainId, nodeIdToElementId, semanticEdgeOwnerElementIds, mappingIntentExecutionNodeIds);
  }

  public MaterializationMap withOwners(
      Map<String, String> nodeIdToElementId,
      Map<String, String> semanticEdgeOwnerElementIds,
      Map<String, String> mappingIntentExecutionNodeIds) {
    return new MaterializationMap(
        chainId,
        nodeIdToElementId,
        semanticEdgeOwnerElementIds,
        mappingIntentExecutionNodeIds);
  }

  private static Map<String, String> copy(Map<String, String> map) {
    return map == null || map.isEmpty() ? Map.of() : Map.copyOf(map);
  }
}
