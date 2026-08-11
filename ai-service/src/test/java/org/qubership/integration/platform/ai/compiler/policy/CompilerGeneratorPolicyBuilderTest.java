package org.qubership.integration.platform.ai.compiler.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackBuildGenerator;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class CompilerGeneratorPolicyBuilderTest {

  private static final int GENERATOR_COUNT = 24;

  private static CompilerGeneratorPolicy policy;

  @BeforeAll
  static void buildPolicy() throws Exception {
    policy = QipKnowledgePackTestSupport.buildPolicyFromFixture();
  }

  @Test
  void buildsPolicyFromFixturePack() {
    assertFalse(policy.generators().isEmpty());
    assertTrue(
        policy.generators().stream()
            .anyMatch(descriptor -> "cip-error-handling-generator".equals(descriptor.skillId())));
    assertTrue(
        policy.generators().stream()
            .anyMatch(descriptor -> "cip-security-generator".equals(descriptor.skillId())));
    assertTrue(
        policy.generators().stream()
            .filter(descriptor -> "cip-security-generator".equals(descriptor.skillId()))
            .anyMatch(descriptor -> descriptor.readiness() != null));
    assertTrue(
        policy.generators().stream()
            .anyMatch(descriptor -> "cip-script-generator".equals(descriptor.skillId())));
  }

  @Test
  void readinessSignalsForServiceCallGeneratorIncludesIncompleteBindings() {
    assertTrue(
        policy.readinessSignalsFor("cip-service-call-generator")
            .contains("incomplete_service_call_bindings"));
  }

  @Test
  void readinessSignalsForSecurityGeneratorIncludesRbacRolesMissing() {
    assertTrue(policy.readinessSignalsFor("cip-security-generator").contains("rbac_roles_missing"));
  }

  @Test
  void policyIncludesExpectedGenerators() {
    List<String> expectedOrder =
        List.of(
            "cip-trigger-generator",
            "cip-error-handling-generator",
            "cip-auth-generator",
            "cip-service-call-generator",
            "cip-routing-generator",
            "cip-timeout-generator",
            "cip-retry-generator",
            "cip-script-generator",
            "cip-composition-generator",
            "cip-loop-generator",
            "cip-parallel-generator",
            "cip-security-generator",
            "cip-monitoring-generator",
            "cip-naming-generator",
            "cip-mcp-service-generator",
            "cip-mcp-trigger-generator",
            "cip-chain-failure-handler-generator",
            "cip-file-operations-generator",
            "cip-sftp-trigger-generator",
            "cip-sds-trigger-generator",
            "cip-messaging-generator",
            "cip-context-storage-generator",
            "cip-xslt-generator",
            "cip-abac-generator");

    assertEquals(GENERATOR_COUNT, policy.generators().size());
    assertEquals(
        expectedOrder,
        policy.generators().stream().map(CompilerGeneratorDescriptor::skillId).toList());
  }

  @Test
  void failsWhenExecutionOrderSectionMissing() {
    CompilerGeneratorPolicyParseException error =
        assertThrows(
            CompilerGeneratorPolicyParseException.class,
            () -> GeneratorContractsParser.parseExecutionOrder("# Generator Contracts\n"));
    assertTrue(error.getMessage().contains("Generator Execution Order"));
  }

  @Test
  void writesPolicyDuringPackBuild() throws Exception {
    QipKnowledgePackTestSupport.configureAddonPackRoot();
    Path outputDir = Files.createTempDirectory("policy-build-test");
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();
    QipKnowledgePackBuildGenerator.generate(packRoot, outputDir);
    Path policyFile =
        outputDir
            .resolve(QipKnowledgePackVersion.fromPath(packRoot).normalized())
            .resolve("compiler-generator-policy.json");
    assertTrue(Files.isRegularFile(policyFile));
    assertTrue(Files.readString(policyFile).contains("cip-security-generator"));
  }
}
