package org.qubership.integration.platform.ai.chat.model;

import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.List;

public class ChatRequest {

  private String conversationId;
  private String message;
  private String attachment;
  private ScenarioType scenarioHint;
  private List<String> attachmentObjectKeys;
  private String resolvedEffectiveUserText;

  public String getConversationId() { return conversationId; }
  public void setConversationId(String conversationId) { this.conversationId = conversationId; }

  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }

  public String getAttachment() { return attachment; }
  public void setAttachment(String attachment) { this.attachment = attachment; }

  public ScenarioType getScenarioHint() { return scenarioHint; }
  public void setScenarioHint(ScenarioType scenarioHint) { this.scenarioHint = scenarioHint; }

  public List<String> getAttachmentObjectKeys() { return attachmentObjectKeys; }
  public void setAttachmentObjectKeys(List<String> attachmentObjectKeys) {
    this.attachmentObjectKeys = attachmentObjectKeys;
  }

  public String getEffectiveUserText() {
    return resolvedEffectiveUserText != null ? resolvedEffectiveUserText : message;
  }

  public void setResolvedEffectiveUserText(String text) { this.resolvedEffectiveUserText = text; }
}
