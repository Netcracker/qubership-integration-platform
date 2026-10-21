package org.qubership.integration.platform.ai.qipknowledge.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/** Computes canonical SHA-256 digests for immutable chain-plan graph snapshots. */
@ApplicationScoped
public class CanonicalGraphDigest {

  private final ObjectMapper objectMapper;

  @Inject
  public CanonicalGraphDigest(ObjectMapper objectMapper) {
    this.objectMapper =
        Objects.requireNonNull(objectMapper, "objectMapper")
            .copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  public String sha256(ChainPlanGraph graph) {
    Objects.requireNonNull(graph, "graph");
    try {
      byte[] payload = objectMapper.writeValueAsBytes(graph);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize ChainPlanGraph for digest", e);
    }
  }
}
