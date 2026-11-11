package org.qubership.integration.platform.ai.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

class S3ControllerTest {
  private final S3Service service = mock(S3Service.class);
  private final S3Controller controller = new S3Controller(service);

  @ParameterizedTest
  @ValueSource(strings = {
      "conversation-archive/Y2hhdA/source.json",
      "product-pipeline-runs/chat-create-chain-2.json",
      "compiler-artifacts/11111111-1111-1111-1111-111111111111.json",
      "sessions/../conversation-archive/source.json",
      "ids-designs/11111111-1111-1111-1111-111111111111/source.json"
  })
  void rejectsInternalAndMalformedKeysWithoutReadingStorage(String key) {
    assertThrows(NotFoundException.class, () -> controller.download(key));
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "11111111-1111-1111-1111-111111111111.json",
      "sessions/old-session/11111111-1111-1111-1111-111111111111.yaml",
      "e2e/import-spec/run-1/11111111-1111-1111-1111-111111111111.json",
      "ids-designs/11111111-1111-1111-1111-111111111111/ids.md"
  })
  void downloadsExistingAttachmentsAndGeneratedIds(String key) throws Exception {
    byte[] content = "saved public document".getBytes(StandardCharsets.UTF_8);
    var stream = new ResponseInputStream<>(GetObjectResponse.builder().build(),
        AbortableInputStream.create(new ByteArrayInputStream(content)));
    when(service.getObject(key)).thenReturn(new S3Object(stream, "document", "text/plain"));

    try (var response = controller.download(key)) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ((StreamingOutput) response.getEntity()).write(output);
      assertEquals(200, response.getStatus());
      assertEquals("saved public document", output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void uploadsSessionAttachmentsWithoutChangingTheirKeyFormat(@TempDir Path directory) throws Exception {
    Path file = Files.writeString(directory.resolve("spec.json"), "{}");
    FileUpload upload = mock(FileUpload.class);
    when(upload.uploadedFile()).thenReturn(file);
    when(upload.fileName()).thenReturn("spec.json");
    when(upload.contentType()).thenReturn("application/json");

    var result = controller.upload(upload, "sessions/old-session");

    org.junit.jupiter.api.Assertions.assertTrue(result.objectKey().startsWith("sessions/old-session/"));
    verify(service).putObject(eq(result.objectKey()), any(), eq(2L), eq("application/json"));
  }

  @Test
  void rejectsUploadsIntoInternalNamespaces(@TempDir Path directory) throws Exception {
    FileUpload upload = mock(FileUpload.class);
    when(upload.uploadedFile()).thenReturn(Files.writeString(directory.resolve("source.json"), "{}"));
    when(upload.fileName()).thenReturn("source.json");

    assertThrows(BadRequestException.class, () -> controller.upload(upload, "conversation-archive/chat"));
    verifyNoInteractions(service);
  }
}
