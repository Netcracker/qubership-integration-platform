package org.qubership.integration.platform.ai.integration.apihub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiHubMcpUrlHelperTest {

  @Test
  void normalizeTrailingSlashAppendsSlashAfterMcpSegment() {
    assertEquals(
        "https://apihub.example.com/api/v1/mcp/",
        ApiHubMcpUrlHelper.normalizeTrailingSlash("https://apihub.example.com/api/v1/mcp"));
  }

  @Test
  void normalizeTrailingSlashLeavesAlreadySlashTerminated() {
    String u = "https://apihub.example.com/api/v1/mcp/";
    assertEquals(u, ApiHubMcpUrlHelper.normalizeTrailingSlash(u));
  }

  @Test
  void splitOriginAndPathHttpsWithPath() {
    ApiHubMcpUrlHelper.OriginAndPath p =
        ApiHubMcpUrlHelper.splitOriginAndPath("https://apihub.example.com/api/v1/mcp/");
    assertEquals("https://apihub.example.com", p.origin());
    assertEquals("/api/v1/mcp/", p.path());
  }

  @Test
  void splitOriginAndPathIncludesNonDefaultPort() {
    ApiHubMcpUrlHelper.OriginAndPath p =
        ApiHubMcpUrlHelper.splitOriginAndPath("https://apihub.example.com:8443/foo/bar");
    assertEquals("https://apihub.example.com:8443", p.origin());
    assertEquals("/foo/bar", p.path());
  }

  @Test
  void splitOriginAndPathRejectsMissingHost() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiHubMcpUrlHelper.splitOriginAndPath("https:///api/v1/mcp"));
  }
}
