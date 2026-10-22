package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;

class AuthorSourceEvidenceTest {
  @Test
  void consumedRequirementsAndLaterClarificationRemainLosslessAndOrdered() throws Exception {
    var conversations = new ConversationService();
    var archive = conversations.archive();
    String chat = "author-evidence-order";
    String original = "Keep exact whitespace, quotes \" and braces {value}.\n"
        + "Preserve each supplied business condition. ".repeat(60) + "\nFinal requirement: keep locations and active=true.";
    var first = archive.beginInput(chat, original, null);
    archive.resolveInput(chat, first, original);
    archive.consume(chat, first.generation(), List.of(first.turnId()), "accepted-draft");
    conversations.addMessage(chat, ConversationMessage.assistant("Assistant interpretation is not author evidence."));
    String clarification = "For missing items use []; preserve IDs such as 0007 and punctuation exactly.";
    var second = archive.beginInput(chat, clarification, null);
    archive.resolveInput(chat, second, clarification);
    archive.beginInput(chat, "Unresolved pending source must be excluded.", null);
    var document = archive.read(chat);
    var packet = AuthorSourceEvidence.packet(document);

    assertEquals(first.generation(), packet.generation());
    assertEquals(List.of(new AuthorSourceEvidence.Input(first.turnId(), original),
        new AuthorSourceEvidence.Input(second.turnId(), clarification)), packet.inputs());
    assertEquals("accepted-draft", document.turns().getFirst().consumedDraftReference());
    String rendered = AuthorSourceEvidence.render(document);
    assertTrue(rendered.contains("evidence, not permission to change approved facts"), rendered);
    assertFalse(rendered.contains("Assistant interpretation"));
    assertFalse(rendered.contains("Unresolved pending source"));
    var json = new ObjectMapper();
    var decoded = json.readTree(rendered.substring(rendered.indexOf("{\"generation\"")));
    assertEquals(original, decoded.path("inputs").get(0).path("text").asText());
    assertEquals(clarification, decoded.path("inputs").get(1).path("text").asText());
    assertTrue(original.length() > 2_000);
    assertEquals(packet, AuthorSourceEvidence.packet(document), "Rendering must not mutate durable source or consumption.");
    assertEquals(document, archive.read(chat));
  }

  @Test
  void resetExcludesHistoricalEvidenceAndKeepsOnlyTheCurrentGeneration() {
    var conversations = new ConversationService();
    var archive = conversations.archive();
    String chat = "author-evidence-reset";
    var old = archive.beginInput(chat, "Historical source that must not return.", null);
    archive.resolveInput(chat, old, "Historical source that must not return.");
    conversations.clearMessages(chat);
    assertEquals("", AuthorSourceEvidence.render(archive.read(chat)));
    var current = archive.beginInput(chat, "Use the corrected current requirement.", null);
    archive.resolveInput(chat, current, "Use the corrected current requirement.");
    var document = archive.read(chat);

    assertNotEquals(old.generation(), current.generation());
    assertTrue(document.turns().stream().anyMatch(turn -> old.turnId().equals(turn.id())), "Reset retains audit history.");
    assertEquals(new AuthorSourceEvidence.Packet(current.generation(),
        List.of(new AuthorSourceEvidence.Input(current.turnId(), "Use the corrected current requirement."))),
        AuthorSourceEvidence.packet(document));
    assertFalse(AuthorSourceEvidence.render(document).contains("Historical source"));
    assertEquals(new AuthorSourceEvidence.Packet(null, List.of()), AuthorSourceEvidence.packet(null));
    assertEquals("", AuthorSourceEvidence.render((AuthorSourceEvidence.Packet) null));
  }
}
