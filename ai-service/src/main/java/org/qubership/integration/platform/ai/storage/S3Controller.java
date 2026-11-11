package org.qubership.integration.platform.ai.storage;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.nio.file.Files;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Upload and download objects stored in S3-compatible storage (e.g. MinIO). Used by the UI for
 * chat attachments; keys are passed in {@code ChatRequest#attachmentObjectKeys} or legacy download
 * URLs in {@code ChatRequest#attachment}.
 */
@Path("/api/v1/storage/objects")
public class S3Controller {

  private static final Logger LOG = Logger.getLogger(S3Controller.class);
  private static final String UUID_KEY =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
  private static final String PUBLIC_PREFIX =
      "(?:sessions|e2e)/[A-Za-z0-9_-][A-Za-z0-9._-]*(?:/[A-Za-z0-9_-][A-Za-z0-9._-]*)*";
  private static final Pattern UPLOAD_PREFIX = Pattern.compile(PUBLIC_PREFIX);
  private static final Pattern PUBLIC_KEY = Pattern.compile(
      "(?:" + PUBLIC_PREFIX + "/)?" + UUID_KEY + "(?:\\.[\\p{L}\\p{N}._-]+)?"
          + "|ids-designs/" + UUID_KEY + "/ids\\.md");

  private final S3Service s3Service;

  @Inject
  S3Controller(S3Service s3Service) {
    this.s3Service = s3Service;
  }

  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.APPLICATION_JSON)
  @Blocking
  public StorageUploadResponse upload(
      @RestForm("file") FileUpload file, @RestForm("prefix") String prefix) throws Exception {

    if (file == null || file.uploadedFile() == null) {
      throw new BadRequestException("file is required");
    }

    String filename = file.fileName() != null ? file.fileName() : "upload";
    int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
    String baseName = slash >= 0 ? filename.substring(slash + 1) : filename;
    int dot = baseName.lastIndexOf('.');
    String ext = dot >= 0 ? baseName.substring(dot) : "";
    String objectKey = UUID.randomUUID() + ext;
    if (prefix != null && !prefix.isBlank()) {
      String normalizedPrefix = prefix.trim().replaceAll("/+$", "");
      if (!UPLOAD_PREFIX.matcher(normalizedPrefix).matches()) {
        throw new BadRequestException("prefix must identify a session or an E2E upload");
      }
      objectKey = normalizedPrefix + "/" + objectKey;
    }
    if (!PUBLIC_KEY.matcher(objectKey).matches()) {
      throw new BadRequestException("Unsupported attachment extension");
    }

    long size = Files.size(file.uploadedFile());
    String contentType =
        file.contentType() != null && !file.contentType().isBlank()
            ? file.contentType()
            : MediaType.APPLICATION_OCTET_STREAM;

    try (var in = Files.newInputStream(file.uploadedFile())) {
      s3Service.putObject(objectKey, in, size, contentType);
    }

    LOG.infof("Uploaded object key=%s size=%d", objectKey, size);
    return new StorageUploadResponse(objectKey, size, contentType);
  }

  @GET
  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  @Blocking
  public Response download(@QueryParam("key") String key) {
    if (key == null || key.isBlank()) {
      throw new BadRequestException("key is required");
    }
    if (!PUBLIC_KEY.matcher(key).matches()) {
      throw new NotFoundException();
    }
    S3Object s3Object = s3Service.getObject(key);
    StreamingOutput streaming = out -> {
      try (var resp = s3Object.resp()) {
        resp.transferTo(out);
      }
    };
    return Response.ok(streaming)
        .type(s3Object.ct())
        .header("Content-Disposition", "attachment; filename=\"" + s3Object.filename() + "\"")
        .build();
  }
}
