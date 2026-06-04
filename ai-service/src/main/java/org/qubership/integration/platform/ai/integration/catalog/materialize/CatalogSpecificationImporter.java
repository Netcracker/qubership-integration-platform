package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogImportRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.ImportSpecificationDto;
import org.qubership.integration.platform.ai.integration.catalog.client.SpecificationFileForm;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;

/**
 * Imports OpenAPI documents into runtime-catalog the same way as the UI: {@code
 * POST /v1/specificationGroups/import} with multipart {@code files}, then poll {@code GET
 * /v1/import/{importId}} until done.
 */
@ApplicationScoped
public class CatalogSpecificationImporter {

  private static final Logger LOG = Logger.getLogger(CatalogSpecificationImporter.class);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(400);
  private static final Duration POLL_TIMEOUT = Duration.ofMinutes(2);

  private final CatalogImportRestClient importRestClient;
  private final CatalogRestClient catalogRestClient;

  @Inject
  public CatalogSpecificationImporter(
      @RestClient CatalogImportRestClient importRestClient,
      @RestClient CatalogRestClient catalogRestClient) {
    this.importRestClient = importRestClient;
    this.catalogRestClient = catalogRestClient;
  }

  public record ImportOutcome(String specificationId, String specificationGroupId, String importId) {}

  /**
   * @param groupName specification group name (UI query param {@code name}); required
   * @param protocol optional protocol query param (omit when null/blank)
   */
  public ImportOutcome importOpenApiDocument(
      String systemId, String groupName, String protocol, byte[] openapiBytes) {
    return importOpenApiDocument(systemId, groupName, protocol, openapiBytes, "openapi.json");
  }

  /**
   * @param fileName multipart file name (e.g. {@code openapi.json} or {@code openapi.yaml})
   */
  public ImportOutcome importOpenApiDocument(
      String systemId,
      String groupName,
      String protocol,
      byte[] openapiBytes,
      String fileName) {
    if (CatalogStrings.blankToNull(systemId) == null) {
      throw new IllegalArgumentException("systemId is required");
    }
    if (CatalogStrings.blankToNull(groupName) == null) {
      throw new IllegalArgumentException("specification group name is required");
    }
    if (openapiBytes == null || openapiBytes.length == 0) {
      throw new IllegalArgumentException("OpenAPI document is empty");
    }
    String resolvedName = groupName.trim();
    String resolvedProtocol = CatalogStrings.blankToNull(protocol);

    SpecificationFileForm form = new SpecificationFileForm();
    form.file = new ByteArrayInputStream(openapiBytes);
    form.fileName =
        CatalogStrings.blankToNull(fileName) != null ? fileName.trim() : "openapi.json";

    LOG.infof(
        "Catalog spec import: POST /v1/specificationGroups/import systemId=%s name=%s file=%s bytes=%d",
        systemId,
        resolvedName,
        form.fileName,
        openapiBytes.length);

    ImportSpecificationDto accepted =
        importRestClient.importSpecificationGroup(
            systemId, resolvedName, resolvedProtocol, form);
    if (accepted == null || CatalogStrings.blankToNull(accepted.id()) == null) {
      throw new IllegalStateException("Catalog import returned no import id");
    }
    String importId = accepted.id();
    String specificationGroupId = accepted.specificationGroupId();
    waitForImportDone(importId);

    String specificationId = resolveSpecificationId(systemId, specificationGroupId);
    LOG.infof(
        "Catalog spec import done: importId=%s groupId=%s specId=%s systemId=%s",
        importId,
        specificationGroupId,
        specificationId,
        systemId);
    return new ImportOutcome(specificationId, specificationGroupId, importId);
  }

  private void waitForImportDone(String importId) {
    long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      ImportSpecificationDto status = importRestClient.getImportStatus(importId);
      if (status != null && status.done()) {
        if (CatalogStrings.blankToNull(status.warningMessage()) != null) {
          LOG.warnf(
              "Catalog spec import finished with warning importId=%s: %s",
              importId,
              status.warningMessage());
        }
        return;
      }
      sleepQuietly(POLL_INTERVAL);
    }
    throw new IllegalStateException(
        "Catalog import timed out after " + POLL_TIMEOUT.toSeconds() + "s importId=" + importId);
  }

  private static void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Catalog import polling interrupted", e);
    }
  }

  private String resolveSpecificationId(String systemId, String specificationGroupId) {
    List<CatalogRestClient.SpecificationDto> models =
        catalogRestClient.getApiSpecifications(systemId);
    if (models == null || models.isEmpty()) {
      throw new IllegalStateException(
          "No specifications found after import for systemId=" + systemId);
    }
    if (CatalogStrings.blankToNull(specificationGroupId) != null) {
      for (CatalogRestClient.SpecificationDto model : models) {
        if (model != null
            && specificationGroupId.equals(model.specificationGroupId())
            && CatalogStrings.blankToNull(model.id()) != null) {
          return model.id();
        }
      }
    }
    CatalogRestClient.SpecificationDto first = models.get(0);
    if (first == null || CatalogStrings.blankToNull(first.id()) == null) {
      throw new IllegalStateException(
          "Catalog models list has no usable specification id for systemId=" + systemId);
    }
    return first.id();
  }
}
