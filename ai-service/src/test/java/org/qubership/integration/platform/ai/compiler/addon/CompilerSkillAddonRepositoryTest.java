package org.qubership.integration.platform.ai.compiler.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class CompilerSkillAddonRepositoryTest {

  @Test
  void loadIndexReadsClasspathOnce() throws Exception {
    AtomicInteger indexOpens = new AtomicInteger();
    byte[] indexJson =
        new ObjectMapper().writeValueAsBytes(CompilerSkillAddonIndex.empty());
    ClassLoader classLoader =
        new ClassLoader(null) {
          @Override
          public InputStream getResourceAsStream(String name) {
            if (name != null && name.endsWith(CompilerSkillAddonBuildSupport.ADDON_INDEX_FILE)) {
              indexOpens.incrementAndGet();
              return new ByteArrayInputStream(indexJson);
            }
            return null;
          }
        };
    CompilerSkillAddonRepository repository =
        new CompilerSkillAddonRepository(
            new QipKnowledgePackVersion("test_v1", "test_v1"), null, classLoader);

    repository.loadForSkill("missing-a");
    repository.loadForSkill("missing-b");
    repository.loadRuntimeMetadata("missing-c");

    assertEquals(1, indexOpens.get());
  }
}
