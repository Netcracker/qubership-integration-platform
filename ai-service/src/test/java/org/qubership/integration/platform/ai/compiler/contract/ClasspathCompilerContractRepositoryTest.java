package org.qubership.integration.platform.ai.compiler.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClasspathCompilerContractRepositoryTest {

  private static final String V1 = "create-chain-compiler-contract/v1";

  private final ClasspathCompilerContractRepository repository =
      new ClasspathCompilerContractRepository();

  @Test
  void loadsV1ContractVersionDigestAndAsyncCardinality() {
    CompilerContract contract = repository.require(V1);
    assertEquals(1, contract.topology().get("split-async-2").minimumBranches());
    assertTrue(contract.sha256().matches("[0-9a-f]{64}"));
    assertEquals(V1, contract.contractVersion());
    assertEquals("chain-semantic-revision/v1", contract.semanticSchemaVersion());
  }

  @Test
  void rejectsUnsupportedContractVersion() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> repository.require("create-chain-compiler-contract/v0"));
    assertEquals(
        "Unsupported compiler contract version: create-chain-compiler-contract/v0",
        error.getMessage());
  }

  @Test
  void rejectsDuplicateYamlKeys() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                ClasspathCompilerContractRepository.parse(
                    """
                    contractVersion: create-chain-compiler-contract/v1
                    contractVersion: create-chain-compiler-contract/v1
                    semanticSchemaVersion: chain-semantic-revision/v1
                    """));
    assertTrue(error.getMessage().contains("duplicate key"));
  }

  @Test
  void rejectsEmptyRequiredArtifactIdentifier() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                ClasspathCompilerContractRepository.parse(
                    """
                    contractVersion: create-chain-compiler-contract/v1
                    semanticSchemaVersion: chain-semantic-revision/v1
                    elements: {}
                    topology: {}
                    requiredArtifacts:
                      - ""
                    requiredAddons:
                      - cip-design-executor
                    requiredKnowledgeFragments: []
                    """));
    assertTrue(error.getMessage().contains("Required artifact identifier must not be empty"));
  }

  @Test
  void containsRequiredElementMappings() {
    CompilerContract contract = repository.require(V1);
    assertTrue(contract.elements().containsKey("http-trigger"));
    assertTrue(contract.elements().containsKey("kafka-trigger-2"));
    assertTrue(contract.elements().containsKey("service-call"));
    assertTrue(contract.elements().containsKey("script"));
    assertTrue(contract.elements().containsKey("mapper-2"));
    assertTrue(contract.elements().containsKey("condition"));
    assertTrue(contract.elements().containsKey("split-2"));
    assertTrue(contract.elements().containsKey("split-async-2"));
    assertTrue(contract.elements().containsKey("loop-2"));
    assertTrue(contract.elements().containsKey("try-catch-finally-2"));
    assertFalse(contract.topology().get("generic-barrier").supported());
    assertTrue(contract.requiredArtifacts().contains("CHAIN_SEMANTIC_REVISION"));
    assertTrue(contract.requiredArtifacts().contains("CHAIN_PLAN_GRAPH"));
    assertTrue(contract.requiredArtifacts().contains("MATERIALIZATION_MAP"));
    assertTrue(contract.requiredAddons().contains("cip-design-executor"));
    assertTrue(contract.requiredAddons().contains("cip-structure-generator"));
  }
}
