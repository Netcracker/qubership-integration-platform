package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.MultipartForm;

/**
 * Multipart REST client for runtime-catalog specification group import ({@code
 * POST /v1/specificationGroups/import}) and async import status ({@code GET /v1/import/{importId}}).
 *
 * <p>Separate from {@link CatalogRestClient} because catalog import uses {@code multipart/form-data},
 * not JSON.
 */
@RegisterRestClient(configKey = "catalog-api")
@RegisterProvider(CatalogOutboundLoggingFilter.class)
@Produces(MediaType.APPLICATION_JSON)
public interface CatalogImportRestClient {

  @POST
  @Path("/v1/specificationGroups/import")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  ImportSpecificationDto importSpecificationGroup(
      @QueryParam("systemId") String systemId,
      @QueryParam("name") String name,
      @QueryParam("protocol") String protocol,
      @MultipartForm SpecificationFileForm form);

  @GET
  @Path("/v1/import/{importId}")
  ImportSpecificationDto getImportStatus(@PathParam("importId") String importId);
}
