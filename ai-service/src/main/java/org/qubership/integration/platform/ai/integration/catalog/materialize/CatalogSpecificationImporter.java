package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogImportRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.ImportSpecificationDto;
import org.qubership.integration.platform.ai.integration.catalog.client.SpecificationFileForm;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
  private static final ObjectMapper DOCUMENT_MAPPER = new YAMLMapper();
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

  public ImportOutcome importOpenApiDocument(
      String systemId, String groupName, String protocol, byte[] openapiBytes) {
    return importOpenApiDocument(systemId, groupName, protocol, openapiBytes, "openapi.json");
  }

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

    Path tempFile = createTempSpecFile(openapiBytes, fileName);
    try {
      SpecificationFileForm form = buildForm(tempFile);

      LOG.infof(
          "Catalog spec import: POST /v1/specificationGroups/import systemId=%s name=%s file=%s bytes=%d",
          systemId,
          resolvedName,
          form.file.getName(),
          openapiBytes.length);

      ImportSpecificationDto accepted =
          importRestClient.importSpecificationGroup(
              systemId, resolvedName, resolvedProtocol, form);
      if (accepted == null || CatalogStrings.blankToNull(accepted.id()) == null) {
        throw new IllegalStateException("Catalog import returned no import id");
      }
      return finishImport(systemId, accepted, openapiBytes);
    } finally {
      deleteTempFile(tempFile);
    }
  }

  /**
   * Imports an OpenAPI document into an existing specification group. Used when a previous upload
   * already created the group so the catalog refuses a duplicate name.
   */
  public ImportOutcome importOpenApiDocumentIntoGroup(
      String systemId, String specificationGroupId, byte[] openapiBytes, String fileName) {
    if (CatalogStrings.blankToNull(systemId) == null) {
      throw new IllegalArgumentException("systemId is required");
    }
    if (CatalogStrings.blankToNull(specificationGroupId) == null) {
      throw new IllegalArgumentException("specificationGroupId is required");
    }
    if (openapiBytes == null || openapiBytes.length == 0) {
      throw new IllegalArgumentException("OpenAPI document is empty");
    }

    Path tempFile = createTempSpecFile(openapiBytes, fileName);
    try {
      SpecificationFileForm form = buildForm(tempFile);

      LOG.infof(
          "Catalog spec import: POST /v1/import systemId=%s specificationGroupId=%s file=%s bytes=%d",
          systemId, specificationGroupId, form.file.getName(), openapiBytes.length);

      ImportSpecificationDto accepted =
          importRestClient.importSpecification(specificationGroupId, form);
      if (accepted == null || CatalogStrings.blankToNull(accepted.id()) == null) {
        throw new IllegalStateException("Catalog import returned no import id");
      }
      return finishImport(systemId, accepted, openapiBytes);
    } finally {
      deleteTempFile(tempFile);
    }
  }

  private static SpecificationFileForm buildForm(Path tempFile) {
    SpecificationFileForm form = new SpecificationFileForm();
    form.file = tempFile.toFile();
    return form;
  }

  private static Path createTempSpecFile(byte[] content, String fileName) {
    String resolvedName =
        CatalogStrings.blankToNull(fileName) != null ? fileName.trim() : "openapi.json";
    try {
      Path tempDir = Files.createTempDirectory("spec-import-");
      Path tempFile = tempDir.resolve(resolvedName);
      Files.write(tempFile, content);
      return tempFile;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to prepare spec temp file", e);
    }
  }

  private static void deleteTempFile(Path tempFile) {
    if (tempFile == null) {
      return;
    }
    try {
      Files.deleteIfExists(tempFile);
      Path parent = tempFile.getParent();
      if (parent != null) {
        Files.deleteIfExists(parent);
      }
    } catch (IOException e) {
      LOG.warnf(e, "Failed to delete temporary spec file %s", tempFile);
    }
  }

  private ImportOutcome finishImport(String systemId, ImportSpecificationDto accepted, byte[] content) {
    String importId = accepted.id();
    String specificationGroupId = accepted.specificationGroupId();
    waitForImportDone(importId);

    String specificationId = resolveSpecificationId(systemId, specificationGroupId, content);
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
              importId, status.warningMessage());
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

  private String resolveSpecificationId(String systemId, String specificationGroupId, byte[] content) {
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
            && CatalogStrings.blankToNull(model.id()) != null
            && sameDocument(content, catalogRestClient.getModelSource(model.id()))) {
          return model.id();
        }
      }
    }
    throw new IllegalStateException(
        "Imported specification source was not found in group=" + specificationGroupId + " systemId=" + systemId);
  }

  public static boolean sameDocument(byte[] uploadedContent, String existingSource) {
    if (uploadedContent == null || existingSource == null) {
      return false;
    }
    try {
      JsonNode uploaded = DOCUMENT_MAPPER.readTree(uploadedContent);
      JsonNode existing = DOCUMENT_MAPPER.readTree(existingSource);
      return uploaded != null && uploaded.equals(existing);
    } catch (Exception ignored) {
      String uploaded = new String(uploadedContent, StandardCharsets.UTF_8);
      return normalizeText(uploaded).equals(normalizeText(existingSource));
    }
  }

  private static String normalizeText(String value) {
    return value.replace("\r\n", "\n").replace('\r', '\n').trim();
  }
}
