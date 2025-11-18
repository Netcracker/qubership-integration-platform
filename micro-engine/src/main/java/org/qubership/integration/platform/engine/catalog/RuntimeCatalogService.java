package org.qubership.integration.platform.engine.catalog;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestResponse;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeploymentsDTO;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentsUpdate;

import java.io.InputStream;

@RegisterRestClient(configKey = "runtime-catalog")
@RegisterProvider(RuntimeCatalogResponseExceptionMapper.class)
public interface RuntimeCatalogService {
    @POST
    @Path("/v1/catalog/domains/{domain}/deployments/update")
    DeploymentsUpdate getDeploymentsUpdate(@PathParam("domain") String domain, EngineDeploymentsDTO excluded);

    @GET
    @Path("/v1/models/{id}/dto/jar")
    RestResponse<InputStream> getDtoJar(@PathParam("id") String id);
}
