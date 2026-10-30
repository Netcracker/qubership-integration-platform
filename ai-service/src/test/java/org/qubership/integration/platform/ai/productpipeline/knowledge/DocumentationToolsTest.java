package org.qubership.integration.platform.ai.productpipeline.knowledge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMemoryIds;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;

class DocumentationToolsTest {
  private static final String CHECKSUM = "sha256:" + "a".repeat(64);
  private final KnowledgeSidecarApi api = mock(KnowledgeSidecarApi.class);
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
  private final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
  private final CompilationSessions sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
  private final SidecarKnowledgeClient client = new SidecarKnowledgeClient(api);

  private DocumentationTools tools() { return new DocumentationTools(client, artifacts, sessions, json); }

  private void documentation() {
    when(api.documentationManifest()).thenReturn(new KnowledgeSidecarApi.DocumentationManifestDto(1, CHECKSUM, 1, 1));
    when(api.searchDocumentation(any())).thenAnswer(call -> new KnowledgeSidecarApi.DocumentationSearchDto(
        call.<KnowledgeSidecarApi.DocumentationSearchRequestDto>getArgument(0).expectedChecksum(),
        List.of(new KnowledgeSidecarApi.DocumentationSectionDto("guide.md#0", "guide.md", "Guide", List.of("Guide"),
            "/doc/guide", "Excerpt", 0, 5))));
    when(api.readDocumentation(any())).thenAnswer(call -> {
      var request = call.<KnowledgeSidecarApi.DocumentationReadRequestDto>getArgument(0);
      return new KnowledgeSidecarApi.DocumentationReadDto(request.expectedChecksum(), request.id(), "guide.md",
          "Guide", "/doc/guide", List.of("Guide"), 0, 5, "hello", null, true, null);
    });
  }

  @Test
  void modelInstructionsPreserveDocumentationUrlsAndExactSourceLiterals() throws Exception {
    documentation();
    var searchTool = DocumentationTools.class
        .getMethod("searchDocumentation", String.class, String.class)
        .getAnnotation(dev.langchain4j.agent.tool.Tool.class);
    var readTool = DocumentationTools.class
        .getMethod("readDocumentation", String.class, String.class, String.class)
        .getAnnotation(dev.langchain4j.agent.tool.Tool.class);
    String context = tools().context("conversation", "REST API errors", List.of());

    String searchInstructions = String.join(" ", searchTool.value());
    String readInstructions = String.join(" ", readTool.value());
    assertTrue(searchInstructions.contains("Copy each returned documentation `url` verbatim"), searchInstructions);
    assertTrue(searchInstructions.contains("do not add a scheme or host"), searchInstructions);
    assertTrue(readInstructions.contains("Copy each returned documentation `url` verbatim"), readInstructions);
    assertTrue(readInstructions.contains("do not add a scheme or host"), readInstructions);
    assertTrue(readInstructions.contains("do not normalize, merge, or deduplicate entries"), readInstructions);
    assertTrue(readInstructions.contains("including paths that differ only by a leading `/`"), readInstructions);
    assertTrue(context.contains("Copy each returned documentation `url` verbatim"), context);
    assertTrue(context.contains("do not add a scheme or host"), context);
    assertTrue(context.contains("do not normalize, merge, or deduplicate entries"), context);
    assertTrue(context.contains("including paths that differ only by a leading `/`"), context);
  }

  @Test
  void compilerContextAndToolReadsUseTheSamePersistedPinAndRecordSourceText() throws Exception {
    documentation();
    String context = tools().context("conversation", "service-call response", List.of("service-call"));
    var read = json.readTree(tools().readDocumentation(
        CompilerSkillMemoryIds.forSkill("conversation", "cip-service-call-generator"), "guide.md#0", null));
    assertAll(
        () -> assertEquals("hello", read.path("text").asText()),
        () -> assertEquals(CHECKSUM, read.path("checksum").asText()),
        () -> assertTrue(context.contains("hello"), context),
        () -> assertTrue(context.contains("/doc/guide"), context));
    verify(api, times(1)).documentationManifest();
    var history = artifacts.history(sessions.active("conversation"), CompilationArtifacts.Kind.DOCUMENTATION_READ);
    assertEquals(3, history.size());
    assertEquals("hello", history.getLast().payload().path("text").asText());
  }

  @Test
  void newConsultationTurnSelectsCurrentCorpusWithoutChangingCompilerPin() throws Exception {
    documentation();
    var tools = tools();
    tools.context("conversation", "script", List.of());
    tools.beginConsultationTurn("consultation:conversation");
    tools.searchDocumentation("consultation:conversation", "script");
    String next = "sha256:" + "b".repeat(64);
    when(api.documentationManifest()).thenReturn(new KnowledgeSidecarApi.DocumentationManifestDto(1, next, 1, 1));
    tools.beginConsultationTurn("consultation:conversation");
    var current = json.readTree(tools.searchDocumentation("consultation:conversation", "script"));
    assertEquals(next, current.path("checksum").asText());
    assertEquals(CHECKSUM, artifacts.latest(sessions.active("conversation"),
        CompilationArtifacts.Kind.DOCUMENTATION_PIN).orElseThrow().payload().path("checksum").asText());
  }

  @Test
  void rejectsDifferentCorpusAndInvalidUtf8ByteRangeInsteadOfPresentingText() {
    documentation();
    org.mockito.Mockito.doReturn(new KnowledgeSidecarApi.DocumentationReadDto(
        "sha256:" + "b".repeat(64), "guide.md#0", "guide.md", "Guide", "/doc/guide", List.of(),
        0, 5, "hello", null, true, null)).when(api).readDocumentation(any());
    assertEquals(KnowledgeFailureKind.DOCUMENTATION_VERSION_MISMATCH,
        assertThrows(KnowledgeClientException.class, () -> client.readDocumentation(CHECKSUM, "guide.md#0", null, 5)).kind());
    org.mockito.Mockito.doReturn(new KnowledgeSidecarApi.DocumentationReadDto(
        CHECKSUM, "guide.md#0", "guide.md", "Guide", "/doc/guide", List.of(), 0, 1, "\u044f", null, true, null))
        .when(api).readDocumentation(any());
    assertEquals(KnowledgeFailureKind.DOCUMENTATION_INTEGRITY_FAILURE,
        assertThrows(KnowledgeClientException.class, () -> client.readDocumentation(CHECKSUM, "guide.md#0", null, 5)).kind());
  }

  @Test
  void unavailabilityIsExplicitAndDoesNotFabricateARead() throws Exception {
    when(api.documentationManifest()).thenThrow(new KnowledgeClientException(
        KnowledgeFailureKind.DOCUMENTATION_UNAVAILABLE, "Documentation is unavailable"));
    var result = json.readTree(tools().searchDocumentation("conversation", "script"));
    assertAll(
        () -> assertFalse(result.path("ok").asBoolean(), result.toString()),
        () -> assertEquals("DOCUMENTATION_UNAVAILABLE", result.path("code").asText()));
    assertEquals(List.of(), artifacts.history(sessions.active("conversation"), CompilationArtifacts.Kind.DOCUMENTATION_READ));
  }
}
