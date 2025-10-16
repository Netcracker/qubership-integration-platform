package org.qubership.integration.platform.engine.catalog;

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

@Slf4j
@Blocking
public class RuntimeCatalogResponseExceptionMapper implements ResponseExceptionMapper<RuntimeException> {
    @Override
    public RuntimeException toThrowable(Response response) {
        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            log.error("Failed to make call to runtime catalog, code: {}, body: {}",
                    response.getStatus(), response.readEntity(String.class));
            return new RuntimeException(
                    "Failed to make call to runtime catalog, response with non 2xx code");
        }
        return null;
    }
}
