package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackTestSupport;

@Tag("certified-pack")
class RockySkillsPinContractTest {
  @Test
  void productionPinsUseJavaMetadataWithoutPlaceholderSkills() throws Exception {
    var pack = CipKnowledgePackTestSupport.installPack().repository();
    var documents = new CompilerSkillDocumentService(pack);
    var resolver = new ProductPipelineRuntimeProducers().compilerRunPinResolver(pack, documents);
    var profile = new ObjectMapper(new YAMLFactory()).readValue(
        CipKnowledgePackFixturePaths.packRoot().resolve(
            "runtime/product-pipelines/profiles/create-chain-v2.yaml").toFile(), ProductPipelineProfile.class);
    var pin = resolver.resolve(profile, FakeKnowledgeClient.defaultFixture().context());
    for (String stage : java.util.List.of("cip-design-planner", "cip-design-executor")) {
      assertEquals(pack.loadManifest().addonSha256().get(stage), pin.skillSha256ById().get(stage));
      assertFalse(java.nio.file.Files.exists(CipKnowledgePackFixturePaths.packRoot().resolve(
          "skills/" + stage + "/SKILL.md")));
    }
    assertTrue(pin.skillSha256ById().containsKey("cip-chain-edit-planner"));
  }
}
