package org.qubership.integration.platform.ai.chat.model;

/**
 * A typed answer to a decision card, sent alongside the message on the chat endpoint.
 *
 * <p>Carries the binding the reader was shown, so the run advances only when the card still matches
 * the open gate. Presence of this field bypasses the scenario router and the intent classifier.
 */
public class ChatDecisionCommand {

  private String action;
  private String artifactType;
  private String artifactHash;
  private long revision;
  private String comment;

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public String getArtifactType() {
    return artifactType;
  }

  public void setArtifactType(String artifactType) {
    this.artifactType = artifactType;
  }

  public String getArtifactHash() {
    return artifactHash;
  }

  public void setArtifactHash(String artifactHash) {
    this.artifactHash = artifactHash;
  }

  public long getRevision() {
    return revision;
  }

  public void setRevision(long revision) {
    this.revision = revision;
  }

  /** Optional remark the reader typed beside the buttons. */
  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }
}
