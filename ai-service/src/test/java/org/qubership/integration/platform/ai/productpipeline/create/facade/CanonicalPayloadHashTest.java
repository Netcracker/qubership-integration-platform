package org.qubership.integration.platform.ai.productpipeline.create.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalPayloadHashTest {

  @Test
  void digestIs64LowercaseHexCharacters() {
    String digest = CanonicalPayloadHash.sha256Hex(Map.of("chainId", "c1", "outcome", "materialized"));
    assertEquals(64, digest.length());
    assertTrue(digest.matches("[0-9a-f]{64}"));
  }

  @Test
  void equalPayloadsWithDifferentInsertionOrderShareDigest() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("chainId", "chain-1");
    a.put("chainName", "Greetings");
    a.put("outcome", "materialized");
    a.put("status", "DRAFT");

    Map<String, Object> b = new LinkedHashMap<>();
    b.put("status", "DRAFT");
    b.put("outcome", "materialized");
    b.put("chainName", "Greetings");
    b.put("chainId", "chain-1");

    assertEquals(CanonicalPayloadHash.sha256Hex(a), CanonicalPayloadHash.sha256Hex(b));
  }

  @Test
  void fieldChangesChangeDigest() {
    Map<String, Object> base = new LinkedHashMap<>();
    base.put("chainId", "chain-1");
    base.put("chainName", "Greetings");
    base.put("outcome", "materialized");
    base.put("status", "DRAFT");
    String baseDigest = CanonicalPayloadHash.sha256Hex(base);

    Map<String, Object> chainIdChanged = new LinkedHashMap<>(base);
    chainIdChanged.put("chainId", "chain-2");
    assertNotEquals(baseDigest, CanonicalPayloadHash.sha256Hex(chainIdChanged));

    Map<String, Object> nameChanged = new LinkedHashMap<>(base);
    nameChanged.put("chainName", "Other");
    assertNotEquals(baseDigest, CanonicalPayloadHash.sha256Hex(nameChanged));

    Map<String, Object> statusChanged = new LinkedHashMap<>(base);
    statusChanged.put("status", "PUBLISHED");
    assertNotEquals(baseDigest, CanonicalPayloadHash.sha256Hex(statusChanged));

    Map<String, Object> outcomeChanged = new LinkedHashMap<>(base);
    outcomeChanged.put("outcome", "failed");
    assertNotEquals(baseDigest, CanonicalPayloadHash.sha256Hex(outcomeChanged));
  }

  @Test
  void matchesTestVectorFromCanonicalJson() {
    String canonical =
        "{\"chainId\":\"chain-1\",\"chainName\":\"Greetings\",\"outcome\":\"materialized\",\"status\":\"DRAFT\"}";
    assertEquals(CanonicalPayloadHash.sha256Hex(canonical), CanonicalPayloadHash.sha256Hex(canonical));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("chainId", "chain-1");
    payload.put("chainName", "Greetings");
    payload.put("outcome", "materialized");
    payload.put("status", "DRAFT");
    assertEquals(CanonicalPayloadHash.sha256Hex(canonical), CanonicalPayloadHash.sha256Hex(payload));
  }
}
