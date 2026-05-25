package org.qubership.integration.platform.ai.chat.prompt;

/** Prompt assembly profile for scenario handlers (RAG source, block order, user header). */
public enum PromptProfile {
  PLANNING("schema", null, 4, "Element Schema Reference", true, true, true, true, true),
  IMPLEMENT("schema", null, 4, "Element Schema Reference", true, true, true, true, false),
  DESIGN("docs", null, 4, "Relevant QIP Knowledge", true, false, false, false, true),
  ASK_DESIGN(null, null, 5, "QIP Knowledge Context", true, true, false, false, true),
  GENERAL(null, null, 5, "QIP Knowledge Context", true, false, false, false, false),
  TESTING("docs", null, 5, "QIP Testing Context", true, false, false, false, false),
  CHAIN_TO_DESIGN("docs", "doc", 4, "IDS Template Reference", true, false, false, false, false),
  MINIMAL(null, null, 0, null, false, false, false, false, false);

  private final String ragSource;
  private final String ragElementTypeFilter;
  private final int ragMaxResults;
  private final String ragHeading;
  private final boolean includeAuthoritativeState;
  private final boolean includeDiary;
  private final boolean includePlanAppendix;
  private final boolean includeApiHubNote;
  private final boolean userRequestHeader;

  PromptProfile(
      String ragSource,
      String ragElementTypeFilter,
      int ragMaxResults,
      String ragHeading,
      boolean includeAuthoritativeState,
      boolean includeDiary,
      boolean includePlanAppendix,
      boolean includeApiHubNote,
      boolean userRequestHeader) {
    this.ragSource = ragSource;
    this.ragElementTypeFilter = ragElementTypeFilter;
    this.ragMaxResults = ragMaxResults;
    this.ragHeading = ragHeading;
    this.includeAuthoritativeState = includeAuthoritativeState;
    this.includeDiary = includeDiary;
    this.includePlanAppendix = includePlanAppendix;
    this.includeApiHubNote = includeApiHubNote;
    this.userRequestHeader = userRequestHeader;
  }

  public String ragSource() {
    return ragSource;
  }

  public String ragElementTypeFilter() {
    return ragElementTypeFilter;
  }

  public int ragMaxResults() {
    return ragMaxResults;
  }

  public String ragHeading() {
    return ragHeading;
  }

  public boolean includeAuthoritativeState() {
    return includeAuthoritativeState;
  }

  public boolean includeDiary() {
    return includeDiary;
  }

  public boolean includePlanAppendix() {
    return includePlanAppendix;
  }

  public boolean includeApiHubNote() {
    return includeApiHubNote;
  }

  public boolean userRequestHeader() {
    return userRequestHeader;
  }

  public boolean usesRag() {
    return ragMaxResults > 0 && ragHeading != null;
  }
}
