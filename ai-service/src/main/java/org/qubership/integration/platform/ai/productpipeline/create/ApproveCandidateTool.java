package org.qubership.integration.platform.ai.productpipeline.create;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;

/**
 * Lets a model express an approval as a typed call instead of a verdict word.
 *
 * <p>The model must name the artifact type, the content hash, and the run revision it approves, so
 * it cannot approve in general — only the exact candidate under review. Nothing here advances a
 * run: the caller validates the named binding against the open gate and runs the command. Chain
 * creation is deliberately absent, matching the public contract that refuses a caller-initiated
 * implement action.
 */
@ApplicationScoped
public class ApproveCandidateTool {

  private static final Logger LOG = Logger.getLogger(ApproveCandidateTool.class);

  private static final ThreadLocal<AtomicReference<Binding>> REQUESTED = new ThreadLocal<>();

  /** Exactly what the model named. */
  public record Binding(String artifactType, String artifactHash, long revision) {}

  /**
   * Collects what the model approves during one agent turn.
   *
   * <p>The turn is blocking, so a thread-local sink is enough — the same shape {@link
   * ProductCapabilityCaptureContext} uses for capture tools.
   */
  public static AutoCloseable capture(AtomicReference<Binding> sink) {
    REQUESTED.set(sink);
    return REQUESTED::remove;
  }

  @Tool(
      "Approve the exact candidate the pipeline is waiting on. Call this only when the reply"
          + " accepts that candidate as it stands, and pass the artifact type, content hash, and"
          + " revision exactly as given in the question.")
  public String approveCandidate(
      @P("public artifact type of the candidate under review") String artifactType,
      @P("content hash of the candidate under review") String artifactHash,
      @P("run revision the candidate belongs to") long revision) {
    AtomicReference<Binding> sink = REQUESTED.get();
    if (sink == null) {
      LOG.warnf("approveCandidate called outside an approval turn; ignoring");
      return "No approval is open.";
    }
    sink.set(new Binding(artifactType, artifactHash, revision));
    return "Approval recorded for " + artifactType + " " + artifactHash + ".";
  }
}
