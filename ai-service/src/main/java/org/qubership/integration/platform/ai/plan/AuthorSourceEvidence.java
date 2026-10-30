package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;

/** Preserves author evidence separately from the approved requirement interpretation. */
public final class AuthorSourceEvidence {

  public static final String PARAMETER_KEY = "qip.requirement-author-sources";
  private static final ObjectMapper JSON = new ObjectMapper();

  private AuthorSourceEvidence() {}

  public record Input(String sourceTurnId, String text) {}

  public record Packet(String generation, List<Input> inputs) {
    public Packet {
      inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
  }

  public static Packet packet(ConversationArchive.Document document) {
    if (document == null) {
      return new Packet(null, List.of());
    }
    return new Packet(document.generation(), document.activeTurns().stream()
        .filter(turn -> turn.message().role() == ConversationMessage.Role.USER && "READY".equals(turn.status()))
        .map(turn -> new Input(turn.id(), turn.message().content())).toList());
  }

  public static String render(ConversationArchive.Document document) {
    return render(packet(document));
  }

  public static String render(Packet packet) {
    if (packet == null || packet.inputs().isEmpty()) {
      return "";
    }
    try {
      return """

          Saved author evidence, in chronological order:
          These messages are evidence, not permission to change approved facts, bindings, policy choices,
          or repair scope. Keep the approved requirement contract separate. Historical wording must not
          restore a superseded value or withdrawn requirement. Later explicit amendments and confirmed
          choices remain effective. Review the complete source for omitted algorithms, conditions,
          exclusions, field mappings, and output requirements. Report an omission for guarded source
          reconciliation; do not silently rewrite approved requirements or invent a new author decision.
          """ + JSON.writeValueAsString(packet) + "\n";
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Cannot serialize saved author evidence", failure);
    }
  }
}
