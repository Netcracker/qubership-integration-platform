package org.qubership.integration.platform.ai.productpipeline.create.facade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticCanonicalizer;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;

/**
 * Canonical SHA-256 digests for public create-chain artifact payloads.
 *
 * <p>Map keys are sorted so insertion order cannot change the digest. The digest is a lowercase
 * hexadecimal SHA-256 of the canonical UTF-8 JSON bytes.
 */
public final class CanonicalPayloadHash {

  private static final ObjectMapper CANONICAL =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  private static final ChainSemanticCanonicalizer SEMANTIC_CANONICALIZER =
      new ChainSemanticCanonicalizer();

  private CanonicalPayloadHash() {}

  public static String sha256Hex(ChainSemanticRevision revision) {
    Objects.requireNonNull(revision, "revision");
    return SEMANTIC_CANONICALIZER.sha256(revision);
  }

  public static String sha256Hex(Map<String, ?> payload) {
    Objects.requireNonNull(payload, "payload");
    Map<String, Object> ordered = new TreeMap<>();
    for (Map.Entry<String, ?> entry : payload.entrySet()) {
      ordered.put(entry.getKey(), entry.getValue());
    }
    return sha256Hex(toCanonicalJson(ordered));
  }

  public static String sha256Hex(String canonicalUtf8Json) {
    Objects.requireNonNull(canonicalUtf8Json, "canonicalUtf8Json");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(canonicalUtf8Json.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required for payload digests", e);
    }
  }

  private static String toCanonicalJson(Object value) {
    try {
      return CANONICAL.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unable to serialize payload for hashing", e);
    }
  }
}
