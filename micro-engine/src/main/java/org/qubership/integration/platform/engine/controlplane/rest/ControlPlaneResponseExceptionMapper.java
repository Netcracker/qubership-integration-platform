package org.qubership.integration.platform.engine.controlplane.rest;

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;

import java.util.Optional;

@Slf4j
@Blocking
public class ControlPlaneResponseExceptionMapper implements ResponseExceptionMapper<ControlPlaneException> {
    @Override
    public ControlPlaneException toThrowable(Response response) {
        if (isDeletionOfNonExistentRoute(response)) {
            log.warn("Trying to delete non-existent route");
        } else if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            log.error("Failed to make call to control plane, code: {}",
                    response.getStatus());
            return new ControlPlaneException(
                    "Failed to make call to control plane, response with non 2xx code");
        }
        return null;
    }

    private boolean isDeletionOfNonExistentRoute(Response response) {
        return response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()
                && Optional.ofNullable(response.readEntity(String.class))
                        .map(s -> s.contains("route does not exist"))
                        .orElse(false);
    }
}
