package org.qubership.integration.platform.ai.compiler.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.addon.MaterializationRequirements.ElementRequirement;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;

class MaterializationRequirementsLoaderTest {

  @Test
  void requiredPropertiesComeFromCompilerContractNotAddonYaml() {
    CompilerSkillAddonRepository addons = mock(CompilerSkillAddonRepository.class);
    when(addons.readGlobalDataDocument(
            MaterializationRequirementsLoader.MATERIALIZATION_REQUIREMENTS_PATH))
        .thenReturn(
            Optional.of(
                """
                version: 1
                elementRequirements:
                  http-trigger:
                    ownerGenerator: cip-trigger-generator
                    requiredProperties:
                      - stale-property
                    examples:
                      contextPath: /hello
                """));

    MaterializationRequirements requirements =
        new MaterializationRequirementsLoader(addons).load();
    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);

    ElementRequirement httpTrigger = requirements.elementRequirements().get("http-trigger");
    assertNotNull(httpTrigger);
    assertEquals(
        contract.elements().get("http-trigger").requiredProperties(),
        httpTrigger.requiredProperties());
    assertEquals("cip-trigger-generator", httpTrigger.ownerGenerator());
    assertEquals("/hello", httpTrigger.examples().get("contextPath"));
  }

  @Test
  void loadAppliesContractRequiredPropertiesWhenAddonYamlIsMissing() {
    CompilerSkillAddonRepository addons = mock(CompilerSkillAddonRepository.class);
    when(addons.readGlobalDataDocument(
            MaterializationRequirementsLoader.MATERIALIZATION_REQUIREMENTS_PATH))
        .thenReturn(Optional.empty());

    MaterializationRequirements requirements =
        new MaterializationRequirementsLoader(addons).load();
    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);

    assertEquals(
        contract.elements().get("script").requiredProperties(),
        requirements.elementRequirements().get("script").requiredProperties());
  }

  @Test
  void yamlOnlyExtraTypeDoesNotKeepYamlRequiredProperties() {
    CompilerSkillAddonRepository addons = mock(CompilerSkillAddonRepository.class);
    when(addons.readGlobalDataDocument(
            MaterializationRequirementsLoader.MATERIALIZATION_REQUIREMENTS_PATH))
        .thenReturn(
            Optional.of(
                """
                version: 1
                elementRequirements:
                  yaml-only-extra-type:
                    ownerGenerator: leftover-generator
                    requiredProperties:
                      - stale-property
                    examples:
                      leftover: leftover-value
                """));

    MaterializationRequirements requirements =
        new MaterializationRequirementsLoader(addons, new ClasspathCompilerContractRepository())
            .load();

    ElementRequirement extra =
        requirements.elementRequirements().get("yaml-only-extra-type");
    assertNotNull(extra);
    assertEquals(List.of(), extra.requiredProperties());
    assertEquals("leftover-generator", extra.ownerGenerator());
    assertEquals("leftover-value", extra.examples().get("leftover"));
  }
}
