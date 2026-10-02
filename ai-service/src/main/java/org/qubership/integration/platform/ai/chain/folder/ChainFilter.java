package org.qubership.integration.platform.ai.chain.folder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;

/** One condition on a column of the chains table. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainFilter(
    @Description("Chains table column the condition reads.") Field field,
    @Description(
            "NAME, ID, DESCRIPTION, LABELS: IS, IS_NOT, CONTAINS, DOES_NOT_CONTAIN, STARTS_WITH,"
                + " ENDS_WITH, EMPTY, NOT_EMPTY. STATUS: IN, NOT_IN. CREATED_BY, MODIFIED_BY: IS,"
                + " IS_NOT, CONTAINS, DOES_NOT_CONTAIN. CREATED_AT, MODIFIED_AT: IS_AFTER,"
                + " IS_BEFORE.")
        Condition condition,
    @Description(
            "Value to compare with. STATUS takes a comma-separated list of DEPLOYED, FAILED,"
                + " PROCESSING, DRAFT. CREATED_AT and MODIFIED_AT take an ISO date such as"
                + " 2026-09-30. Empty for EMPTY and NOT_EMPTY.")
        String value) {

  public enum Field {
    NAME,
    ID,
    DESCRIPTION,
    LABELS,
    /** Deployment status across engines. */
    STATUS,
    CREATED_BY,
    CREATED_AT,
    MODIFIED_BY,
    MODIFIED_AT
  }

  public enum Condition {
    IS,
    IS_NOT,
    CONTAINS,
    DOES_NOT_CONTAIN,
    STARTS_WITH,
    ENDS_WITH,
    EMPTY,
    NOT_EMPTY,
    IN,
    NOT_IN,
    IS_AFTER,
    IS_BEFORE
  }
}
