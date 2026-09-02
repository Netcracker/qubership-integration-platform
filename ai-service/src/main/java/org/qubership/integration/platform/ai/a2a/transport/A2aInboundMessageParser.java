package org.qubership.integration.platform.ai.a2a.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;

/**
 * Parses inbound A2A Message parts into create-chain commands.
 *
 * <p>Rejects a public {@code implement} action. Does not decide which recovery path applies —
 * that comes from the facade pending-action descriptor.
 */
public final class A2aInboundMessageParser {

  private A2aInboundMessageParser() {}

  public static InboundCommand parse(Message message) throws A2AError {
    Objects.requireNonNull(message, "message");
    List<Part<?>> parts = message.parts();
    if (parts == null || parts.isEmpty()) {
      throw A2aProtocolErrorMapper.malformedStructuredData("Message parts are required");
    }

    List<String> texts = new ArrayList<>();
    Map<String, Object> structured = null;
    for (Part<?> part : parts) {
      if (part instanceof TextPart textPart) {
        if (textPart.text() != null && !textPart.text().isBlank()) {
          texts.add(textPart.text());
        }
      } else if (part instanceof DataPart dataPart) {
        if (structured != null) {
          throw A2aProtocolErrorMapper.malformedStructuredData(
              "Only one structured data part is supported");
        }
        Object raw = dataPart.data();
        if (!(raw instanceof Map<?, ?> map)) {
          throw A2aProtocolErrorMapper.malformedStructuredData("Structured data must be an object");
        }
        Map<String, Object> copied = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String key)) {
            throw A2aProtocolErrorMapper.malformedStructuredData(
                "Structured data keys must be strings");
          }
          copied.put(key, entry.getValue());
        }
        structured = copied;
      } else if (part instanceof FilePart) {
        throw A2aProtocolErrorMapper.unsupportedContentType(
            "File parts are not supported by create-chain@2");
      } else {
        throw A2aProtocolErrorMapper.unsupportedContentType(
            "Unsupported part type: " + part.getClass().getSimpleName());
      }
    }

    if (structured != null) {
      return parseStructured(structured, String.join("\n", texts));
    }
    String joined = String.join("\n", texts);
    return new InboundCommand.ClarifyText(canonicalMappingGapAction(joined));
  }

  @SuppressWarnings("unchecked")
  private static InboundCommand parseStructured(Map<String, Object> data, String freeText)
      throws A2AError {
    if (data == null || data.isEmpty()) {
      throw A2aProtocolErrorMapper.malformedStructuredData("Structured data is empty");
    }
    Object actionValue = data.get("action");
    if (!(actionValue instanceof String action) || action.isBlank()) {
      throw A2aProtocolErrorMapper.malformedStructuredData("Structured data requires action");
    }
    if ("implement".equalsIgnoreCase(action)) {
      throw A2aProtocolErrorMapper.unsupportedImplementAction();
    }
    if ("approve".equalsIgnoreCase(action)) {
      Object type = data.get("artifactType");
      Object hash = data.get("artifactHash");
      Object revision = data.get("revision");
      if (!(type instanceof String artifactType) || artifactType.isBlank()) {
        throw A2aProtocolErrorMapper.malformedStructuredData("approve requires artifactType");
      }
      if (!(hash instanceof String artifactHash) || artifactHash.isBlank()) {
        throw A2aProtocolErrorMapper.malformedStructuredData("approve requires artifactHash");
      }
      long rev = toRevision(revision);
      String comment = null;
      Object commentValue = data.get("comment");
      if (commentValue instanceof String commentText && !commentText.isBlank()) {
        comment = commentText;
      } else if (freeText != null && !freeText.isBlank()) {
        comment = freeText;
      }
      return new InboundCommand.Approve(artifactType, artifactHash, rev, comment);
    }
    if ("clarify".equalsIgnoreCase(action)) {
      String text = freeText;
      Object reason = data.get("reason");
      if ((text == null || text.isBlank()) && reason instanceof String reasonText) {
        text = reasonText;
      }
      Object evidence = data.get("clarificationText");
      if ((text == null || text.isBlank()) && evidence instanceof String clarification) {
        text = clarification;
      }
      if (text == null || text.isBlank()) {
        throw A2aProtocolErrorMapper.malformedStructuredData("clarify requires clarification text");
      }
      return new InboundCommand.ClarifyText(text);
    }
    if (PipelineGates.RETRY_ACTION.equalsIgnoreCase(action)) {
      return new InboundCommand.ClarifyText(PipelineGates.RETRY_ACTION);
    }
    if (PipelineGates.REVISE_ACTION.equalsIgnoreCase(action)) {
      return new InboundCommand.ClarifyText(PipelineGates.REVISE_ACTION);
    }
    if ("pass_through".equalsIgnoreCase(action) || "PASS_THROUGH".equalsIgnoreCase(action)) {
      return new InboundCommand.ClarifyText("pass_through");
    }
    if ("describe_mappings".equalsIgnoreCase(action)) {
      return new InboundCommand.ClarifyText("describe_mappings");
    }
    throw A2aProtocolErrorMapper.malformedStructuredData("Unsupported action: " + action);
  }

  static String canonicalMappingGapAction(String text) {
    if (text == null) {
      return "";
    }
    String trimmed = text.trim();
    if ("pass_through".equalsIgnoreCase(trimmed) || "PASS_THROUGH".equalsIgnoreCase(trimmed)) {
      return "pass_through";
    }
    if ("describe_mappings".equalsIgnoreCase(trimmed)) {
      return "describe_mappings";
    }
    return text;
  }

  private static long toRevision(Object revision) throws A2AError {
    if (revision instanceof Number number) {
      return number.longValue();
    }
    if (revision instanceof String text) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException e) {
        throw A2aProtocolErrorMapper.malformedStructuredData("approve revision must be a number");
      }
    }
    throw A2aProtocolErrorMapper.malformedStructuredData("approve requires revision");
  }

  public static final String KIND_INITIAL_CLARIFY = "initial-clarify";
  public static final String KIND_CONTINUE_CLARIFY = "continue-clarify";
  public static final String KIND_APPROVE = "approve";

  /**
   * Labels the receipt with the resumable facade command kind.
   *
   * <p>The kind comes from the parsed Message alone. It never inspects Task status, revision, or
   * pipeline state: which internal steps an approval still needs is decided by durable command
   * evidence inside the facade, not by the transport.
   */
  public static String commandKind(InboundCommand command, boolean isNew) {
    if (command instanceof InboundCommand.ClarifyText) {
      return isNew ? KIND_INITIAL_CLARIFY : KIND_CONTINUE_CLARIFY;
    }
    if (command instanceof InboundCommand.Approve) {
      return KIND_APPROVE;
    }
    throw new IllegalArgumentException("Unsupported command: " + command.getClass().getName());
  }

  public sealed interface InboundCommand {
    record ClarifyText(String text) implements InboundCommand {
      public ClarifyText {
        text = text == null ? "" : text;
      }
    }

    record Approve(String artifactType, String artifactHash, long revision, String comment)
        implements InboundCommand {
      public Approve {
        Objects.requireNonNull(artifactType, "artifactType");
        Objects.requireNonNull(artifactHash, "artifactHash");
        comment = comment == null ? "" : comment;
      }
    }
  }
}
