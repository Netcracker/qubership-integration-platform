package org.qubership.integration.platform.ai.integration.catalog.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CatalogAuthorizationCaptureFilterTest {

  @Test
  void capturesChatAndHarnessPathsOnly() {
    assertTrue(CatalogAuthorizationCaptureFilter.shouldCapture("/api/v1/chat"));
    assertTrue(CatalogAuthorizationCaptureFilter.shouldCapture("/api/ui/v1/chat"));
    assertTrue(CatalogAuthorizationCaptureFilter.shouldCapture("/api/v1/harness/skills/run"));
    assertFalse(CatalogAuthorizationCaptureFilter.shouldCapture("/api/v1/storage/objects"));
    assertFalse(CatalogAuthorizationCaptureFilter.shouldCapture(null));
  }
}
