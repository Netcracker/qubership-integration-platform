package org.qubership.integration.platform.ai.chain.patch;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.schema.ChainElementCatalog;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * Answers what an element type accepts, so a patch can configure one without guessing.
 *
 * <p>A model adding an element it has not seen in the open chain writes its configuration from
 * memory, and memory is wrong: {@code url} and {@code method} on a {@code service-call}, which the
 * schema does not define, or {@code mapper} for a type the catalog calls {@code mapper-2}. Both are
 * refused, correctly, as unowned -- and the same schemas that refuse them can answer the question
 * instead. This tool turns that knowledge from a gate into a source.
 *
 * <p>Not folded into the request text. The available type names already ship there; adding every
 * type's property keys would make a reference block longer than the change being asked for, and a
 * reference block that outweighs the task measurably costs accuracy on unrelated cases. Asked for,
 * it costs one round trip and only when something is actually being added.
 */
@ApplicationScoped
public class ChainElementTypeTool {

  private static final Logger LOG = Logger.getLogger(ChainElementTypeTool.class);
  private static final String TOOL = "describeElementType";

  private final DeterministicElementSchemaService schemaService;
  private final ChainElementCatalog elementCatalog;

  @Inject
  public ChainElementTypeTool(
      DeterministicElementSchemaService schemaService, ChainElementCatalog elementCatalog) {
    this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
    this.elementCatalog = Objects.requireNonNull(elementCatalog, "elementCatalog");
  }

  @Tool(
      """
      Look up what an element type accepts before adding an element of that type. Answers with the
      property keys the type allows and the ones it requires. Call this for any type the open chain
      does not already have -- a property key the type does not define is refused, and so is a type
      name the catalog does not know.
      """)
  public String describeElementType(String elementType) {
    String conversationId = ToolSession.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(LOG, TOOL, conversationId, "elementType=" + elementType);

    String result = describe(elementType);
    ToolTraceLog.logToolComplete(LOG, TOOL, conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }

  private String describe(String elementType) {
    if (elementType == null || elementType.isBlank()) {
      return "elementType is required.";
    }
    String type = elementType.trim();
    if (!schemaService.hasElementSchema(type)) {
      return "No element type '" + type + "' exists. " + nearestHint(type);
    }

    Set<String> allowed = new TreeSet<>(schemaService.allowedPatchPropertyKeys(type));
    Set<String> required = new TreeSet<>(schemaService.requiredPatchPropertyKeys(type));
    StringBuilder text = new StringBuilder("Element type '").append(type).append("'.");
    if (elementCatalog.isDeprecated(type)) {
      text.append(" Deprecated -- prefer the current form of this element.");
    }
    text.append(required.isEmpty() ? " No required properties." : " Required: " + String.join(", ", required) + ".");
    text.append(
        allowed.isEmpty()
            ? " It takes no properties."
            : " Accepts: " + String.join(", ", allowed) + ". Use no other key.");
    return text.toString();
  }

  /**
   * The catalog suffixes the current form of several elements, so a guessed name is usually the
   * right element under last year's spelling. Naming the near miss saves a whole turn.
   */
  private String nearestHint(String type) {
    String prefix = type + "-";
    String near =
        elementCatalog.allTypes().stream()
            .filter(candidate -> candidate.startsWith(prefix) || type.startsWith(candidate + "-"))
            .filter(candidate -> !elementCatalog.isDeprecated(candidate))
            .findFirst()
            .orElse(null);
    return near == null
        ? "Use one of the types listed in the request."
        : "Did you mean '" + near + "'?";
  }
}
