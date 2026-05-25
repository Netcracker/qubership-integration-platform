package org.qubership.integration.platform.ai.chat.hitl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Represents a Human-in-the-Loop checkpoint emitted as an SSE event. The frontend presents this to
 * the user and POSTs back an answer.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class HitlCheckpoint {

  private String checkpointId;
  private String conversationId;
  private String question;

  /** Optional list of allowed choices. Null means free-text answer. */
  private List<String> options;
}
