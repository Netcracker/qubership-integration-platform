package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;

class CipKnowledgePackVersionTest {

  @Test
  void fromPathExtractsNormalizedVersionFromFixture() {
    Path packRoot = CipKnowledgePackFixturePaths.packRoot();

    CipKnowledgePackVersion version = CipKnowledgePackVersion.fromPath(packRoot);

    assertEquals(CipKnowledgePackFixturePaths.PACK_DIR, version.normalized());
    assertEquals(CipKnowledgePackFixturePaths.PACK_DIR, version.raw());
  }

  @Test
  void normalizesLegacyVersionStrings() {
    CipKnowledgePackVersion version = new CipKnowledgePackVersion("v1_0_1", "v1_0_1");

    assertEquals("v1_0_1", version.normalized());
    assertEquals("v1_0_1", version.raw());
  }
}
