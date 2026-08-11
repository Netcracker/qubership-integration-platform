package org.qubership.integration.platform.ai.compiler.artifact;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.storage.S3Service;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Persists compiler artifact documents in the configured S3-compatible storage. */
@ApplicationScoped
class S3ArtifactBlobStore implements ArtifactBlobStore {

  private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

  private final S3Service s3Service;

  @Inject
  S3ArtifactBlobStore(S3Service s3Service) {
    this.s3Service = s3Service;
  }

  @Override
  public void put(String key, byte[] content) {
    s3Service.putObject(
        key, new ByteArrayInputStream(content), content.length, JSON_CONTENT_TYPE);
  }

  @Override
  public Optional<byte[]> get(String key) {
    try {
      return Optional.of(s3Service.readObjectUtf8(key).getBytes(StandardCharsets.UTF_8));
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public List<String> list(String prefix) {
    return s3Service.listObjectKeys(prefix);
  }

  @Override
  public Optional<VersionedBlob> getVersioned(String key) {
    try {
      S3Service.ConditionallyVersionedObject loaded = s3Service.getObjectBytesVersioned(key);
      return Optional.of(new VersionedBlob(loaded.content(), loaded.etag()));
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public void putIfVersion(String key, byte[] content, String expectedVersion) {
    try {
      s3Service.putObjectIfVersion(
          key,
          new ByteArrayInputStream(content),
          content.length,
          JSON_CONTENT_TYPE,
          expectedVersion);
    } catch (S3Exception e) {
      if (e.statusCode() == 412) {
        throw new StaleBlobVersionException("stale S3 version for key " + key, e);
      }
      throw e;
    }
  }
}
