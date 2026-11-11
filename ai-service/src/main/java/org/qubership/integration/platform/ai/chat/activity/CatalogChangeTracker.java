package org.qubership.integration.platform.ai.chat.activity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.chat.ChatEvent;

/** Accumulates confirmed mutations for one active request, including writes before a partial failure. */
public final class CatalogChangeTracker {
  private final Set<String> chainIds = new LinkedHashSet<>();
  private boolean broad;
  private boolean listChanged;

  public synchronized void chainChanged(String chainId) {
    if (chainId == null || chainId.isBlank()) {
      broad = true;
    } else {
      chainIds.add(chainId);
    }
    listChanged = true;
  }

  public synchronized void foldersChanged(boolean affectsChainPaths) {
    broad |= affectsChainPaths;
    listChanged = true;
  }

  public synchronized ChatEvent.CatalogChanged drain() {
    if (!listChanged) {
      return null;
    }
    var result = new ChatEvent.CatalogChanged(broad ? null : List.copyOf(chainIds), true);
    chainIds.clear();
    broad = false;
    listChanged = false;
    return result;
  }
}
