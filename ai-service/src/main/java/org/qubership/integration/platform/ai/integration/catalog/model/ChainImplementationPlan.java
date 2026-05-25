package org.qubership.integration.platform.ai.integration.catalog.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Structured chain implementation plan produced before catalog mutations.
 *
 * <p>Mirrors the runtime-catalog chain JSON: tree shape via nested {@link ElementPlan#children},
 * runtime dependency edges via {@link #connections} at the chain root and via {@link
 * ElementPlan#connections} between direct siblings inside each container node.
 *
 * <p>{@code parentClientId} from earlier revisions of the plan has been removed; placement is
 * expressed by where a row sits inside the {@code children} array. {@code clientId} must be unique
 * across the whole plan (including all nested levels) so that connections can reference any pair of
 * siblings on the same level by their {@code clientId}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChainImplementationPlan {

  private ChainSection chain;
  private List<ElementPlan> elements;
  private List<ConnectionPlan> connections;

  /**
   * QIP Integration Design Specification id from the user/IDS (e.g. {@code QIP.INT.IDS.MyFlow}).
   * Optional; links this plan back to the design document for operators and later automation.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String sourceIdsDocumentId;

  /**
   * S3/MinIO object key for the IDS file attached in chat (same namespace as {@code
   * attachmentObjectKeys} on {@code ChatRequest}). Optional when the design was pasted inline only.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String sourceIdsAttachmentObjectKey;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ChainSection {
    private String name;
    private String description;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ElementPlan {
    private String clientId;

    /**
     * Runtime element id in the catalog (same as REST {@code /elements/{elementId}}); filled by
     * server-side materialization, not required from the LLM.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String elementId;

    private String type;
    private String displayName;

    /**
     * Runtime parent element id in the catalog tree; filled by materialization, not required from
     * the LLM.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String parentElementId;

    @JsonAlias({"properties", "expectedProperties"})
    private Map<String, Object> expectedProperties;

    /**
     * Planner-only flag (CREATE_CHAIN_PLAN JSON). Value {@code user_accepted_unbound} means the
     * user accepted building this row without catalog operation binding: omit {@code
     * integrationOperationId} (and related operation fields) in {@link #expectedProperties}.
     * Applies to {@code service-call}, implemented {@code http-trigger}, and {@code
     * async-api-trigger} rows that would otherwise require binding. Server treats it as satisfied
     * binding debt and skips catalog enrich; IMPLEMENT_CHAIN creates the element skeleton and graph
     * edges only (no operation PATCH) unless real catalog ids are already present.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String bindingStatus;

    /**
     * When true, IMPLEMENT_CHAIN should run {@code importApiHubSpecToSystem} (after {@code
     * createSystem} if needed) before PATCHing that {@code service-call}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean importRequired;

    /** Nested elements that live as catalog tree children of this node. */
    private List<ElementPlan> children;

    /**
     * Runtime dependency edges between direct children of this node (siblings on the same level).
     */
    private List<ConnectionPlan> connections;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ConnectionPlan {
    @JsonAlias("from")
    private String fromClientId;

    @JsonAlias("to")
    private String toClientId;
  }
}
