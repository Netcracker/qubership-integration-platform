package org.qubership.integration.platform.ai.chain.patch;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;

/**
 * Takes a restore point for a chain before a change that cannot be undone.
 *
 * <p>Deleting an element is final as far as a patch is concerned: this service creates and
 * reconfigures, and nothing it can do afterwards brings a deleted element back. A catalog snapshot
 * can -- reverting one restores elements under their original ids -- so a snapshot taken first is
 * the only real way back, and offering one before a destructive change is the whole point of this
 * tool.
 *
 * <p>The snapshot is left in the chain's history deliberately. Deleting it afterwards would clear
 * the chain's {@code currentSnapshot} pointer rather than restore whatever it pointed at before,
 * which would cost the chain more than the tidy-up is worth.
 */
@ApplicationScoped
public class ChainSnapshotTool {

  private static final Logger LOG = Logger.getLogger(ChainSnapshotTool.class);
  private static final String TOOL = "createChainSnapshot";

  private final CatalogRestClient catalogRestClient;

  @Inject
  public ChainSnapshotTool(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = Objects.requireNonNull(catalogRestClient, "catalogRestClient");
  }

  @Tool(
      """
      Save a snapshot of the open chain as a restore point, before a change that cannot be undone.
      Offer this before removing anything: removal is final, and reverting to a snapshot is the only
      way back. Give the chain id from the chain graph in the user message.
      """)
  public String createChainSnapshot(String chainId) {
    String conversationId = ToolSession.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(LOG, TOOL, conversationId, "chainId=" + chainId);

    String result = snapshot(chainId);
    ToolTraceLog.logToolComplete(LOG, TOOL, conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }

  private String snapshot(String chainId) {
    if (chainId == null || chainId.isBlank()) {
      return "chainId is required to take a snapshot.";
    }
    try {
      CatalogRestClient.SnapshotDto snapshot = catalogRestClient.createSnapshot(chainId.trim());
      String name = snapshot != null && snapshot.name() != null ? snapshot.name() : "the chain";
      return "Saved snapshot " + name + ". The chain can be reverted to it later.";
    } catch (RuntimeException e) {
      LOG.warnf(e, "Snapshot failed for chain %s", chainId);
      // The catalog refuses to snapshot a chain whose elements do not pass its own property
      // verification, which is common for a chain still being assembled. Say so plainly: the
      // reader has to decide whether to proceed without a way back.
      return "Could not save a snapshot: "
          + e.getMessage()
          + ". A chain with incomplete element configuration cannot be snapshotted.";
    }
  }
}
