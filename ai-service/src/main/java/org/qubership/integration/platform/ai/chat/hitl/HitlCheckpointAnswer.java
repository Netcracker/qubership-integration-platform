package org.qubership.integration.platform.ai.chat.hitl;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** User's answer to a HITL checkpoint, sent from the frontend. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class HitlCheckpointAnswer {

  @NotBlank(message = "answer must not be blank")
  private String answer;
}
