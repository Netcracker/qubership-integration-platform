package org.qubership.integration.platform.ai.storage;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Upload and download objects stored in S3-compatible storage (e.g. MinIO). Used by the UI for chat
 * attachments; keys are passed in {@code ChatRequest#attachmentObjectKeys} or legacy download URLs
 * in {@code ChatRequest#attachment}.
 */
@Path("/api/v1/storage/objects")
public class S3Controller {

  private static final Logger LOG = Logger.getLogger(S3Controller.class);

  @Inject S3Client s3;

  @Inject AppConfig config;

  @Inject S3Service s3Service;

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
      objectKey = normalizedPrefix + "/" + objectKey;
    }

    long size = Files.size(file.uploadedFile());
    String contentType =
        file.contentType() != null && !file.contentType().isBlank()
            ? file.contentType()
            : MediaType.APPLICATION_OCTET_STREAM;

    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(config.storage().bucketName())
            .key(objectKey)
            .contentType(contentType)
            .contentLength(size)
            .build();

    try (InputStream in = Files.newInputStream(file.uploadedFile())) {
      s3.putObject(put, RequestBody.fromInputStream(in, size));
    }

    LOG.infof("Uploaded object key=%s size=%d", objectKey, size);
    return new StorageUploadResponse(objectKey, size, contentType);
  }

  @GET
  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  @Blocking
  public Response download(@QueryParam("key") String key) {
    S3Object s3Object = s3Service.getObject(key);
    return Response.ok(s3Object.resp())
        .type(s3Object.ct())
        .header("Content-Disposition", "attachment; filename=\"" + s3Object.filename() + "\"")
        .build();
  }
}
