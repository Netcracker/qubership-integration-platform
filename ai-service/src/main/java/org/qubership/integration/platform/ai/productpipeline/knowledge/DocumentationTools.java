package org.qubership.integration.platform.ai.productpipeline.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMemoryIds;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;

/** Reads public documentation from one corpus pinned to the current compilation or consultation turn. */
@ApplicationScoped
public class DocumentationTools {
  private static final Logger LOG = Logger.getLogger(DocumentationTools.class);
  private static final int CONTEXT_BYTES = 12_000;
  private static final int SEARCH_QUERY_CHARS = 2_000;
  private final SidecarKnowledgeClient client;
  private final CompilationArtifacts artifacts;
  private final CompilationSessions sessions;
  private final ObjectMapper json;

  @Inject
  public DocumentationTools(SidecarKnowledgeClient client, CompilationArtifacts artifacts,
      CompilationSessions sessions, ObjectMapper json) {
    this.client = client;
    this.artifacts = artifacts;
    this.sessions = sessions;
    this.json = json;
  }

  public void beginConsultationTurn(String memoryId) {
    sessions.startNew(memoryId);
  }

  @Tool("Search QIP product documentation before making platform behavior claims. Use English technical terms "
      + "for English manuals, retaining exact property names and error codes. Results are excerpts, not complete "
      + "sections. Read relevant sections with readDocumentation and cite their url. Missing results do not prove "
      + "that the platform lacks a capability.")
  public String searchDocumentation(@ToolMemoryId String memoryId, String query) {
    return invoke("searchDocumentation", memoryId, () -> {
      String conversation = conversation(memoryId);
      return search(conversation, query, List.of(), 5);
    });
  }

  @Tool("Read a section id returned by searchDocumentation or the supplied documentation context. Pass an empty cursor "
      + "for the first page, then nextCursor until complete when the answer depends on remaining text. Each page "
      + "contains exact source text and a separate display context. complete means this section is complete, not "
      + "that every document relevant to the question was read. Cite the returned url. Documentation does not "
      + "override approved author requirements, schemas, or live catalog evidence.")
  public String readDocumentation(@ToolMemoryId String memoryId, String id, String cursor) {
    return invoke("readDocumentation", memoryId, () -> read(conversation(memoryId), id, cursor, CONTEXT_BYTES));
  }

  public String context(String conversationId, String query, List<String> elementTypes) {
    if (conversationId == null || conversationId.isBlank() || query == null || query.isBlank()) return "";
    var found = search(conversationId, query, elementTypes, 3);
    StringBuilder body = new StringBuilder("\n\nPublic product documentation (reference material):\n");
    body.append("Corpus: ").append(found.checksum()).append('\n');
    int remaining = CONTEXT_BYTES;
    for (var section : found.results()) {
      if (remaining < 4) break;
      var page = read(conversationId, section.id(), null, Math.min(4_000, remaining));
      body.append(encode(page)).append('\n');
      remaining -= page.endByte() - page.startByte();
    }
    if (found.results().isEmpty()) body.append("No matching documentation sections were found.\n");
    body.append("Search excerpts and incomplete pages are not complete evidence. Read the indicated continuation "
        + "when needed. Cite the source url; keep private knowledge citations separate. Manuals describe expected "
        + "behavior, not this environment's state, and do not authorize changing approved requirements.\n");
    return body.toString();
  }

  private KnowledgeSidecarApi.DocumentationSearchDto search(
      String conversationId, String query, List<String> types, int limit) {
    int characters = Math.min(SEARCH_QUERY_CHARS, query.codePointCount(0, query.length()));
    String searchQuery = query.substring(0, query.offsetByCodePoints(0, characters));
    var result = client.searchDocumentation(checksum(conversationId), searchQuery, types, limit);
    record(conversationId, result);
    return result;
  }

  private KnowledgeSidecarApi.DocumentationReadDto read(
      String conversationId, String id, String cursor, int maxBytes) {
    var result = client.readDocumentation(checksum(conversationId), id, cursor, maxBytes);
    record(conversationId, result);
    return result;
  }

  private String checksum(String conversationId) {
    String compilation = sessions.active(conversationId);
    return artifacts.latest(compilation, Kind.DOCUMENTATION_PIN)
        .map(revision -> artifacts.payload(revision, Pin.class).checksum())
        .orElseGet(() -> {
          var manifest = client.documentationManifest();
          artifacts.append(new CompilationArtifacts.AppendCommand(compilation, Kind.DOCUMENTATION_PIN,
              "1", "public-documentation", "1", new Pin(manifest.checksum()), List.of(), null));
          return manifest.checksum();
        });
  }

  private void record(String conversationId, Object result) {
    String compilation = sessions.active(conversationId);
    var pin = artifacts.latest(compilation, Kind.DOCUMENTATION_PIN).orElseThrow();
    artifacts.append(new CompilationArtifacts.AppendCommand(compilation, Kind.DOCUMENTATION_READ,
        "1", "public-documentation", "1", result, List.of(pin.reference()), null));
  }

  private String invoke(String tool, String memoryId, java.util.function.Supplier<Object> action) {
    long start = System.currentTimeMillis();
    String conversation = conversation(memoryId);
    ToolTraceLog.logToolInvoke(LOG, tool, conversation, "public-documentation");
    try {
      String result = encode(action.get());
      ToolTraceLog.logToolComplete(LOG, tool, conversation, System.currentTimeMillis() - start, result);
      return result;
    } catch (KnowledgeClientException error) {
      String result = encode(Map.of("ok", false, "code", error.kind().name(), "message", error.getMessage(),
          "instruction", "State the documentation limitation; do not invent unsupported platform behavior."));
      ToolTraceLog.logToolComplete(LOG, tool, conversation, System.currentTimeMillis() - start, result);
      return result;
    }
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Cannot serialize documentation evidence", error);
    }
  }

  private static String conversation(String memoryId) {
    String id = CompilerSkillMemoryIds.conversationId(memoryId);
    if (id == null || id.isBlank()) throw new IllegalArgumentException("Documentation requires an active conversation");
    return id;
  }

  public record Pin(String checksum) {}
}
