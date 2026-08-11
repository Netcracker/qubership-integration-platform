package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class QipKnowledgePackVersionTest {

  @Test
  void fromPathExtractsNormalizedVersionFromFixture() {
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();

    QipKnowledgePackVersion version = QipKnowledgePackVersion.fromPath(packRoot);

    assertEquals(QipKnowledgePackFixturePaths.PACK_DIR, version.normalized());
    assertEquals(QipKnowledgePackFixturePaths.PACK_DIR, version.raw());
  }

  @Test
  void normalizesLegacyVersionStrings() {
    QipKnowledgePackVersion version = new QipKnowledgePackVersion("v1_0_1", "v1_0_1");

    assertEquals("v1_0_1", version.normalized());
    assertEquals("v1_0_1", version.raw());
  }
}
