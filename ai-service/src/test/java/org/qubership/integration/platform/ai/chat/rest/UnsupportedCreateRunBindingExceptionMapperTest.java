package org.qubership.integration.platform.ai.chat.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.UnsupportedCreateRunBindingException;

class UnsupportedCreateRunBindingExceptionMapperTest {
  @Test
  void retiredBindingReturnsConflictWithCutoverIdentifier() {
    var exception = new UnsupportedCreateRunBindingException();
    var response = new UnsupportedCreateRunBindingExceptionMapper().toResponse(exception);

    assertEquals(409, response.getStatus());
    assertEquals(MediaType.TEXT_PLAIN_TYPE, response.getMediaType());
    assertEquals(exception.sseMessage(), response.getEntity());
  }
}
