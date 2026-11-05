package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

  static Stream<Arguments> approvalSources() {
    return Stream.of(
        Arguments.of("approve", "catalog-operation-version", "", List.of("Approved catalog-operation-version manually-authored")),
        Arguments.of("approve", "implementation-plan", "  ", List.of("Approved catalog-operation-version manually-authored")),
        Arguments.of("approve-and-create", "implementation-plan", "", List.of("Approved catalog-operation-version manually-authored")),
        Arguments.of("approve", "catalog-operation-version", "  Keep \"1.0.0\" selected.\n", List.of(
            "Approved catalog-operation-version manually-authored", "  Keep \"1.0.0\" selected.\n")),
        Arguments.of("approve", "implementation-plan", "Keep the exact business condition.", List.of(
            "Approved catalog-operation-version manually-authored", "Keep the exact business condition.")));
  }

  @ParameterizedTest
  @MethodSource("approvalSources")
  void approvalProtocolDoesNotBecomeAuthoredRequirements(
      String action, String artifactType, String comment, List<String> expectedText) {
    var conversations = new ConversationService();
    var archive = conversations.archive();
    String chat = "approval-source";
    String authored = "Approved catalog-operation-version manually-authored";
    var ordinary = archive.beginInput(chat, authored, null);
    archive.resolveInput(chat, ordinary, authored);
    var command = new org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand();
    command.setAction(action);
    command.setArtifactType(artifactType);
    command.setArtifactHash("confirmed-artifact");
    command.setComment(comment);
    var approval = archive.beginInput(chat, "", null);
    archive.resolveInput(chat, approval,
        org.qubership.integration.platform.ai.chat.service.ChatDecisionService.transcriptMarker(command),
        new ObjectMapper().valueToTree(command));
    var document = archive.read(chat);

    assertEquals(expectedText, AuthorSourceEvidence.authoredPacket(document).inputs().stream()
        .map(AuthorSourceEvidence.Input::text).toList());
    assertEquals(List.of(ordinary.turnId(), approval.turnId()), AuthorSourceEvidence.packet(document).inputs().stream()
        .map(AuthorSourceEvidence.Input::sourceTurnId).toList());
    assertEquals(document, archive.read(chat));
    assertEquals(approval.turnId(),
        org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(document));
    assertEquals(document.generation(), AuthorSourceEvidence.packet(document).generation());
  }

  @Test
  void anExistingArchiveRetainsItsPreupgradeCompletePacketAndHash() {
    String comment = "  Keep \"1.0.0\" selected.\n";
    String archivedText = "Approved catalog-operation-version confirmed-artifact\n\nKeep \"1.0.0\" selected.";
    var metadata = new ObjectMapper().createObjectNode();
    metadata.putObject("resolvedDecision").put("action", "approve").put("comment", comment);
    var turn = new org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Turn(
        "prior-approval", ConversationMessage.user(archivedText), "", metadata, "READY", null);
    var document = new org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Document(
        1, "existing-generation", List.of(turn), List.of("prior-approval"), List.of(), List.of());
    var previousPacket = new AuthorSourceEvidence.Packet("existing-generation", List.of(
        new AuthorSourceEvidence.Input("prior-approval", archivedText)));

    assertEquals(previousPacket, AuthorSourceEvidence.packet(document));
    assertEquals("62808d9ce54ffe9da6f43ce98575c716531ea1266916ee6aa87da507402e04b5",
        org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(AuthorSourceEvidence.packet(document)));
    assertEquals(List.of(new AuthorSourceEvidence.Input("prior-approval", comment)),
        AuthorSourceEvidence.authoredPacket(document).inputs());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"", "  "})
  void emptyOrdinaryAuthorTurnsRemainSourceEvidence(String authored) {
    var archive = new ConversationService().archive();
    var input = archive.beginInput("empty-author-source", authored, null);
    archive.resolveInput("empty-author-source", input, authored);

    assertEquals(List.of(new AuthorSourceEvidence.Input(input.turnId(), authored)),
        AuthorSourceEvidence.packet(archive.read("empty-author-source")).inputs());
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
