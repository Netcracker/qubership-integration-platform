package org.qubership.integration.platform.ai.integration.catalog.descriptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

/**
 * Loads element descriptors from the live runtime-catalog library, not from hardcoded type lists.
 *
 * <p>Each call hits {@code GET /v1/library/{name}}. Unknown types (HTTP 404 or a null body) and
 * transport or parse failures fail closed. The exception message names the requested type. Do not
 * fall back to {@link ChainElementFamilies}, {@code ChainElementCatalog}, or {@code qip-schemas}.
 *
 * <p>This source makes the following {@link ChainElementFamilies} sets redundant for structural
 * questions (container, children, parent, deprecation). Do not delete or empty those sets here:
 *
 * <ul>
 *   <li>{@code TRY_CATCH_WRAPPER}, {@code TRY_CATCH_SHELL}, {@code TRY_CATCH}: container and
 *       generated-child types
 *   <li>{@code ROUTING}, {@code ROUTING_MODERN}, {@code ROUTING_BRANCH_CHILDREN}: condition/if/else
 *       containment
 *   <li>{@code LOOP}, {@code PARALLEL}: container status of loop and split families
 *   <li>{@code ROUTING_DEPRECATED}, {@code TRY_CATCH_DEPRECATED}: deprecation ({@code deprecated} on
 *       the descriptor)
 * </ul>
 *
 * <p>Still semantic, and not replaced by this loader: {@code TRIGGERS} (family membership;
 * descriptors also expose {@code allowedInContainers}) and {@code CHAIN_CALL} (not a containment
 * contract).
 *
 * <p>Callers that need reuse within one materialization attempt should wrap this loader in a
 * {@link CatalogElementDescriptorCache}.
 */
@ApplicationScoped
public class CatalogElementDescriptorLoader {

  private final CatalogRestClient catalogRestClient;

  @Inject
  public CatalogElementDescriptorLoader(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = catalogRestClient;
  }

  /**
   * Fetches the live descriptor for {@code type}. Always calls the catalog; does not cache.
   *
   * @throws CatalogElementDescriptorException if the type is unknown or the catalog cannot be read
   */
  public CatalogElementDescriptor load(String type) {
    CatalogElementDescriptorDto dto;
    try {
      dto = catalogRestClient.getLibraryElement(type);
    } catch (RuntimeException e) {
      throw unloadable(type, e);
    }
    if (dto == null) {
      throw new CatalogElementDescriptorException(type, "not found.");
    }
    return toReadModel(dto);
  }

  private static CatalogElementDescriptorException unloadable(String type, RuntimeException e) {
    if (isNotFound(e)) {
      return new CatalogElementDescriptorException(type, "not found.", e);
    }
    String reason = e.getMessage();
    if (reason == null || reason.isBlank()) {
      reason = e.getClass().getSimpleName();
    }
    return new CatalogElementDescriptorException(type, reason, e);
  }

  private static boolean isNotFound(Throwable e) {
    WebApplicationException wae = CatalogRestSupport.findWebApplicationException(e);
    if (wae == null) {
      return false;
    }
    Response response = wae.getResponse();
    return response != null && response.getStatus() == 404;
  }

  private static CatalogElementDescriptor toReadModel(CatalogElementDescriptorDto dto) {
    return new CatalogElementDescriptor(
        dto.name,
        dto.container,
        dto.allowedChildren,
        dto.parentRestriction,
        dto.ordered,
        dto.priorityProperty,
        dto.mandatoryInnerElement,
        dto.deprecated,
        dto.oldStyleContainer,
        dto.allowedInContainers);
  }
}
