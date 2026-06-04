package org.qubership.integration.platform.ai.chat.planning;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportCandidateBootstrapServiceTest {

  private final ConversationPlanningDiaryService diaryService = new ConversationPlanningDiaryService();
  private final ImportCandidateBootstrapService bootstrapService =
      new ImportCandidateBootstrapService(diaryService);

  @Test
  void bootstrapsCandidateFromAssistantOfferText() {
    String conversationId = "conv-bootstrap";
    diaryService.recordCatalogLookupNote(
        conversationId,
        "searchCatalogSystems",
        "searchCondition=Service Catalog Management",
        "empty",
        "[]");
    List<ConversationMessage> messages =
        List.of(
            new ConversationMessage(
                conversationId,
                ConversationMessage.Role.ASSISTANT,
                "Import the relevant operation from the APIHub package S.ActProv.SvcCat with"
                    + " version 2026.1@1 for the Service Catalog Management."),
            new ConversationMessage(
                conversationId, ConversationMessage.Role.USER, "Import an APIHub operation"));

    Optional<ApiHubImportCandidate> candidate =
        bootstrapService.ensureCandidateForImport(conversationId, messages);

    assertTrue(candidate.isPresent());
    assertEquals("S.ActProv.SvcCat", candidate.get().apiHubPackageId());
    assertEquals("2026.1@1", candidate.get().apiHubVersion());
    assertEquals("Service Catalog Management", candidate.get().catalogSystemName());
  }
}
