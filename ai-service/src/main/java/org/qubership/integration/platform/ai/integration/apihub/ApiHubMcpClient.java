package org.qubership.integration.platform.ai.integration.apihub;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * MicroProfile REST Client for the APIHUB MCP server. Calls the MCP HTTP endpoint to search for
 * REST API operations and retrieve their specs.
 */
@RegisterRestClient(configKey = "apihub-mcp")
@RegisterProvider(ApiHubOutboundLoggingFilter.class)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface ApiHubMcpClient {

  /** POST JSON-RPC request to the configured MCP base URL (e.g. {@code .../api/v1/mcp/}). */
  @POST
  Response post(@HeaderParam("Mcp-Session-Id") String mcpSessionId, JsonNode request);
}
