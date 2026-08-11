package org.qubership.integration.platform.ai.chat.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.ai.chat.evidence.ConversationEvidenceStore;

/** Feature-flagged read-only evidence snapshot for a conversation. */
@Path("/api/v1/chat/conversations/{conversationId}")
public class ConversationEvidenceResource {

  private final ConversationEvidenceStore store;
  private final boolean enabled;

  @Inject
  public ConversationEvidenceResource(
      ConversationEvidenceStore store,
      @ConfigProperty(name = "qip.evidence.snapshot.enabled") boolean enabled) {
    this.store = store;
    this.enabled = enabled;
  }

  @GET
  @Path("/evidence")
  @Produces(MediaType.APPLICATION_JSON)
  public Response evidence(@PathParam("conversationId") String conversationId) {
    if (!enabled) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    return store
        .find(conversationId)
        .map(acc -> Response.ok(acc.toSnapshot(conversationId)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }
}
