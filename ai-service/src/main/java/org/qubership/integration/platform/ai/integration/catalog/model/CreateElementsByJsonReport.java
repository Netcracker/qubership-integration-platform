package org.qubership.integration.platform.ai.integration.catalog.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Structured result of {@link
 * org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogElementsCreatorService#createFromBatchJson}:
 * skeleton materialization, connections, and property patches, each with collected failures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateElementsByJsonReport {

  public String chainId;

  /** Resolved {@code clientId} → catalog element id after skeleton stage. */
  public LinkedHashMap<String, String> clientIds = new LinkedHashMap<>();

  public Stages stages = new Stages();

  /**
   * Property keys removed before PATCH so the catalog request could use only schema-allowed keys.
   */
  public List<SkippedPropertyPatch> skippedPropertyPatches = new ArrayList<>();

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class SkippedPropertyPatch {
    public String clientId;
    public String elementId;
    public String elementType;
    public List<String> removedKeys = new ArrayList<>();
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Stages {
    public StageOutcome skeleton;
    public StageOutcome connections;
    public StageOutcome properties;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class StageOutcome {
    public boolean ok;
    public List<StageFailure> failures = new ArrayList<>();
  }

  /**
   * Optional fields depend on stage: skeleton uses clientId/type/parentElementId; connections uses
   * fromClientId/toClientId; properties uses clientId/elementId.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class StageFailure {
    public String clientId;
    public String type;
    public String parentElementId;
    public String fromClientId;
    public String toClientId;
    public String elementId;
    public String reason;
  }
}
