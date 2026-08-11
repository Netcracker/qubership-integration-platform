package org.qubership.integration.platform.ai.compiler.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.storage.S3Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
class S3ArtifactBlobStoreTest {

  @Mock private S3Client s3Client;
  @Mock private AppConfig appConfig;
  @Mock private AppConfig.StorageConfig storageConfig;

  private S3ArtifactBlobStore store;

  @BeforeEach
  void setUp() {
    when(appConfig.storage()).thenReturn(storageConfig);
    when(storageConfig.bucketName()).thenReturn("test-bucket");
    S3Service s3Service = new S3Service(appConfig, s3Client);
    store = new S3ArtifactBlobStore(s3Service);
  }

  @Test
  void createUsesIfNoneMatchStar() {
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().eTag("\"etag-1\"").build());

    store.putIfVersion("runs/run-1.json", "body".getBytes(StandardCharsets.UTF_8), null);

    ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
    assertEquals("*", captor.getValue().ifNoneMatch());
  }

  @Test
  void updateUsesEtagFromPreviousGetAsIfMatch() {
    byte[] content = "{\"runRevision\":1}".getBytes(StandardCharsets.UTF_8);
    GetObjectResponse response = GetObjectResponse.builder().eTag("\"etag-42\"").build();
    ResponseInputStream<GetObjectResponse> stream =
        new ResponseInputStream<>(
            response, AbortableInputStream.create(new java.io.ByteArrayInputStream(content)));
    when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().eTag("\"etag-43\"").build());

    Optional<VersionedBlob> loaded = store.getVersioned("runs/run-1.json");
    assertTrue(loaded.isPresent());
    assertEquals("\"etag-42\"", loaded.get().version());
    assertArrayEquals(content, loaded.get().content());

    store.putIfVersion("runs/run-1.json", "{}".getBytes(StandardCharsets.UTF_8), loaded.get().version());

    ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
    assertEquals("\"etag-42\"", captor.getValue().ifMatch());
  }

  @Test
  void mapsHttp412ToStaleBlobVersionException() {
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(S3Exception.builder().statusCode(412).message("Precondition Failed").build());

    assertThrows(
        StaleBlobVersionException.class,
        () ->
            store.putIfVersion(
                "runs/run-1.json", "body".getBytes(StandardCharsets.UTF_8), "\"etag-old\""));
  }
}
