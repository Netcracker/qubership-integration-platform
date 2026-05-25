package org.qubership.integration.platform.ai.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageUploadResponseSerializationTest {

  @Test
  void roundTripJsonMatchesRestShape() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    StorageUploadResponse original =
        new StorageUploadResponse("prefix/uuid.txt", 42L, "text/plain");
    String json = mapper.writeValueAsString(original);
    StorageUploadResponse parsed = mapper.readValue(json, StorageUploadResponse.class);
    assertEquals(original.objectKey(), parsed.objectKey());
    assertEquals(original.size(), parsed.size());
    assertEquals(original.contentType(), parsed.contentType());
  }
}
