package org.qubership.integration.platform.ai.plan.workdocument;

/** Server-assigned slice of one immutable source. The model selects the id; it does not invent one. */
public record SourcePassage(
    String id, String sourceId, String contentHash, String text, String parentHeading) {

  public SourcePassage {
    parentHeading = parentHeading == null ? "" : parentHeading;
    text = text == null ? "" : text;
    contentHash = contentHash == null ? "" : contentHash;
    sourceId = sourceId == null ? "" : sourceId;
    id = id == null ? "" : id;
  }
}
