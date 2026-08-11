package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.PartFilename;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;

import java.io.InputStream;

/** Multipart body for {@link CatalogImportRestClient#importSpecificationGroup}. */
public class SpecificationFileForm {

  @RestForm("files")
  @PartType(MediaType.APPLICATION_OCTET_STREAM)
  public InputStream file;

  @RestForm("files")
  @PartFilename("openapi.json")
  public String fileName = "openapi.json";
}
