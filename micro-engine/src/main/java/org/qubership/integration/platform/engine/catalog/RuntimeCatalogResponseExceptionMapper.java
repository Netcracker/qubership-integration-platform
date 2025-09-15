package org.qubership.integration.platform.engine.catalog;

import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

@Slf4j
public class RuntimeCatalogResponseExceptionMapper implements ResponseExceptionMapper<RuntimeException> {
    @Override
    public RuntimeException toThrowable(Response response) {
        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            log.error("Failed to get deployments update from runtime catalog, code: {}, body: {}",
                    response.getStatus(), response.readEntity(String.class));
            return new RuntimeException(
                    "Failed to get deployments update from runtime catalog, response with non 2xx code");
        }
        return null;
    }
}
