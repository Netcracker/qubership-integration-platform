package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CaptureProposalTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void onlyObjectKeyOrderingIsIgnored() throws Exception {
    var first = json.readTree("{\"b\":{\"z\":1,\"a\":\" x \"},\"a\":[1,2]}");
    var reordered = json.readTree("{\"a\":[1,2],\"b\":{\"a\":\" x \",\"z\":1}}");
    assertEquals(CaptureProposal.digest(first), CaptureProposal.digest(reordered));
    for (String changed : new String[] {
        "{\"a\":[2,1],\"b\":{\"a\":\" x \",\"z\":1}}",
        "{\"a\":[1,2],\"b\":{\"a\":\"x\",\"z\":1}}",
        "{\"a\":[1,2],\"b\":{\"a\":\" x \",\"z\":\"1\"}}",
        "{\"a\":[1,2],\"b\":{\"a\":\" x \",\"z\":1.0}}",
        "{\"a\":[1,2],\"b\":{\"a\":\" x \",\"z\":1},\"c\":null}"}) {
      assertNotEquals(CaptureProposal.digest(first), CaptureProposal.digest(json.readTree(changed)));
    }
  }
}
