package org.qubership.integration.platform.engine.service;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.buffer.Buffer;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestHeader;

import java.util.Map;
import java.util.Optional;

@RegisterRestClient(configKey = "checkpoints")
public interface CheckpointRestService {
    @POST
    @Path("/chains/{checkpointChainId}/sessions/{checkpointSessionId}/checkpoint-elements/{checkpointElementId}/retry")
    public Uni<Buffer> retryCheckpoint(
            @PathParam("checkpointChainId") String checkpointChainId,
            @PathParam("checkpointSessionId") String checkpointSessionId,
            @PathParam("checkpointElementId") String checkpointElementId,
            @RestHeader Map<String, String> headers,
            Optional<String> body
    );
}
