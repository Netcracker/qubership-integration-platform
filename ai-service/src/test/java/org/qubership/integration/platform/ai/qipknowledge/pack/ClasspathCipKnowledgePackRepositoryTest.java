package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillDescriptor;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillDisposition;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineEntry;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexBuilder;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexSource;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorDescriptor;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicySource;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorReadiness;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpec;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageArtifact;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageIndex;
import org.qubership.integration.platform.ai.qipknowledge.rag.CipKnowledgeRagChunk;
import org.qubership.integration.platform.ai.qipknowledge.rag.CipKnowledgeRagIngestionManifest;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;
import org.qubership.integration.platform.ai.qipknowledge.skill.CipKnowledgeCapabilityPhase;

class ClasspathCipKnowledgePackRepositoryTest {

  @Test
  void loadsIndexesFromClasspathResources() throws Exception {
    CipKnowledgePackVersion version = new CipKnowledgePackVersion("test_v1", "test_v1");
    ClassLoader classLoader = new TestResourceClassLoader(version);
    ClasspathCipKnowledgePackRepository repository =
        new ClasspathCipKnowledgePackRepository(
            version, classLoader, new ObjectMapper().registerModule(new JavaTimeModule()));

    assertEquals("test_v1", repository.activeVersion().normalized());
    assertEquals("test_v1", repository.loadManifest().version().normalized());
    assertEquals(1, repository.loadCapabilityRegistry().capabilities().size());
    assertEquals(1, repository.loadRagIngestionManifest().chunks().size());
    assertEquals(1, repository.loadUnsupportedItems().size());
    assertEquals("GEN-04", repository.loadCompilerGeneratorPolicy().generators().get(0).generatorId());
    assertEquals(1, repository.loadCompilerPipelineIndex().entries().size());
    assertEquals(
        CompilerSkillDisposition.PUBLIC_RUNTIME,
        repository.loadCompilerSkillCatalog().skills().get(0).disposition());
    assertEquals(
        "GEN-04",
        repository.loadCompilerGeneratorSpecIndex().specs().get(0).generatorId());
    assertEquals(
        "graph-patch-contract",
        repository.loadCompilerRuntimePackageIndex().artifacts().get(0).artifactType());
  }

  @Test
  void loadManifestReadsClasspathOnce() {
    CipKnowledgePackVersion version = new CipKnowledgePackVersion("test_v1", "test_v1");
    java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
    ClassLoader delegate = new TestResourceClassLoader(version);
    ClassLoader classLoader =
        new ClassLoader(null) {
          @Override
          public java.io.InputStream getResourceAsStream(String name) {
            opens.incrementAndGet();
            return delegate.getResourceAsStream(name);
          }
        };
    ClasspathCipKnowledgePackRepository repository =
        new ClasspathCipKnowledgePackRepository(
            version, classLoader, new ObjectMapper().registerModule(new JavaTimeModule()));

    repository.loadManifest();
    int afterFirstManifest = opens.get();
    repository.loadManifest();
    assertEquals(afterFirstManifest, opens.get());

    repository.loadCapabilityRegistry();
    int afterFirstRegistry = opens.get();
    repository.loadCapabilityRegistry();
    assertTrue(afterFirstManifest > 0);
    assertTrue(afterFirstRegistry > afterFirstManifest);
    assertEquals(afterFirstRegistry, opens.get());
  }

  @Test
  void failsFastWhenClasspathResourceIsMissing() {
    ClasspathCipKnowledgePackRepository repository =
        new ClasspathCipKnowledgePackRepository(new CipKnowledgePackVersion("missing", "missing"));

    assertThrows(IllegalStateException.class, repository::loadManifest);
  }

  @Test
  void requireCompilerContractDigestFailsWhenPinIsMissing() {
    CipKnowledgePackVersion version = new CipKnowledgePackVersion("test_v1", "test_v1");
    ClassLoader classLoader = new TestResourceClassLoader(version);
    ClasspathCipKnowledgePackRepository repository =
        new ClasspathCipKnowledgePackRepository(
            version, classLoader, new ObjectMapper().registerModule(new JavaTimeModule()));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, repository::requireCompilerContractDigest);
    assertTrue(error.getMessage().contains("missing compilerContractSha256"));
  }

  @Test
  void requireCompilerContractDigestFailsWhenPinDoesNotMatchContract() {
    CipKnowledgePackVersion version = new CipKnowledgePackVersion("test_v1", "test_v1");
    ClassLoader classLoader =
        new TestResourceClassLoader(version, CompilerContract.V1, "0".repeat(64));
    ClasspathCipKnowledgePackRepository repository =
        new ClasspathCipKnowledgePackRepository(
            version, classLoader, new ObjectMapper().registerModule(new JavaTimeModule()));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, repository::requireCompilerContractDigest);
    assertTrue(error.getMessage().contains("does not match compiler contract"));
  }

  private static final class TestResourceClassLoader extends ClassLoader {

    private final CipKnowledgePackVersion version;
    private final String compilerContractVersion;
    private final String compilerContractSha256;
    private final ObjectMapper objectMapper =
        new ObjectMapper().registerModule(new JavaTimeModule());

    private TestResourceClassLoader(CipKnowledgePackVersion version) {
      this(version, null, null);
    }

    private TestResourceClassLoader(
        CipKnowledgePackVersion version,
        String compilerContractVersion,
        String compilerContractSha256) {
      this.version = version;
      this.compilerContractVersion = compilerContractVersion;
      this.compilerContractSha256 = compilerContractSha256;
    }

    @Override
    public java.io.InputStream getResourceAsStream(String name) {
      if (!name.startsWith("qipknowledge/" + version.normalized() + "/")) {
        return null;
      }
      try {
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        byte[] bytes = resourceBytes(fileName);
        return new java.io.ByteArrayInputStream(bytes);
      } catch (Exception e) {
        throw new IllegalStateException("Failed to build test classpath resource: " + name, e);
      }
    }

    private byte[] resourceBytes(String fileName) throws Exception {
      return switch (fileName) {
        case CipKnowledgePackIndexLoader.MANIFEST_FILE ->
            objectMapper.writeValueAsBytes(
                new CipKnowledgePackManifest(
                        version,
                        "/pack",
                        Instant.parse("2026-06-19T00:00:00Z"),
                        Map.of("skills/a/SKILL.md", "abc"),
                        List.of("cip-test-generator"),
                        List.of("cip-test-generator"),
                        List.of())
                    .withCompilerContractPin(
                        compilerContractVersion, compilerContractSha256, Map.of()));
        case CipKnowledgePackIndexLoader.CAPABILITY_REGISTRY_FILE ->
            objectMapper.writeValueAsBytes(
                new CapabilityRegistry(
                    version,
                    List.of(
                        new org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityDescriptor(
                            "cip-test-generator",
                            "cip-test-generator",
                            version,
                            CipKnowledgeCapabilityPhase.GENERATOR,
                            true,
                            null,
                            List.of(),
                            List.of()))));
        case CipKnowledgePackIndexLoader.RAG_INGESTION_MANIFEST_FILE ->
            objectMapper.writeValueAsBytes(
                new CipKnowledgeRagIngestionManifest(
                    version,
                    List.of(
                        new CipKnowledgeRagChunk(
                            "cip-test-generator",
                            "skills/cip-test-generator/SKILL.md",
                            version,
                            CipKnowledgeCapabilityPhase.GENERATOR,
                            List.of("cip-test-generator"),
                            "cip-test-generator",
                            0,
                            "Test generator content"))));
        case CipKnowledgePackIndexLoader.UNSUPPORTED_ITEMS_FILE ->
            objectMapper.writeValueAsBytes(
                List.of(new UnsupportedCipKnowledgeItem("cip-folder-organizer", "skills/x/SKILL.md", "unsupported")));
        case CipKnowledgePackIndexLoader.COMPILER_GENERATOR_POLICY_FILE ->
            objectMapper.writeValueAsBytes(
                new CompilerGeneratorPolicy(
                    version,
                    new CompilerGeneratorPolicySource("abc", "def"),
                    List.of(
                        new CompilerGeneratorDescriptor(
                            "GEN-04",
                            "cip-test-generator",
                            1,
                            "error-handling-plan.yaml",
                            List.of("R-501"),
                            new CompilerGeneratorReadiness(
                                "ai-service-adapter", List.of("always_ready"))))));
        case CipKnowledgePackIndexLoader.COMPILER_PIPELINE_INDEX_FILE ->
            objectMapper.writeValueAsBytes(
                new CompilerPipelineIndex(
                    CompilerPipelineIndexBuilder.SCHEMA_VERSION,
                    version,
                    new CompilerPipelineIndexSource("abc", "def"),
                    List.of(
                        new CompilerPipelineEntry(
                            "cip-test-generator",
                            "generation",
                            "generation",
                            1,
                            "GEN-04",
                            true,
                            "skills/cip-test-generator/SKILL.md",
                            "abc",
                            "high",
                            List.of(),
                            List.of(),
                            List.of()))));
        case CipKnowledgePackIndexLoader.COMPILER_SKILL_CATALOG_FILE ->
            objectMapper.writeValueAsBytes(
                new CompilerSkillCatalog(
                    List.of(
                        new CompilerSkillDescriptor(
                            "cip-test-generator",
                            "generation",
                            "skills/cip-test-generator/SKILL.md",
                            true,
                            true,
                            false,
                            CompilerSkillDisposition.PUBLIC_RUNTIME,
                            List.of("skills/cip-test-generator/SKILL.md"),
                            null,
                            List.of(),
                            List.of(),
                            List.of()))));
        case CipKnowledgePackIndexLoader.COMPILER_GENERATOR_SPEC_INDEX_FILE ->
            objectMapper.writeValueAsBytes(
                new CompilerGeneratorSpecIndex(
                    List.of(
                        new CompilerGeneratorSpec(
                            "cip-test-generator",
                            "GEN-04",
                            "generation",
                            "generation",
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of("skills/cip-test-generator/SKILL.md")))));
        case CipKnowledgePackIndexLoader.COMPILER_RUNTIME_PACKAGE_INDEX_FILE ->
            objectMapper.writeValueAsBytes(
                new CompilerRuntimePackageIndex(
                    List.of(
                        new CompilerRuntimePackageArtifact(
                            "runtime/graph-patch-contract.json",
                            "graph-patch-contract",
                            "abc",
                            List.of("propertyPatches")))));
        case CipKnowledgePackIndexLoader.PRODUCT_PIPELINE_PACKAGE_INDEX_FILE ->
            objectMapper.writeValueAsBytes(
                new org.qubership.integration.platform.ai.productpipeline.packageindex
                        .ProductPipelinePackageIndex(
                    "baseline",
                    "bd",
                    "cd",
                    "1.0.0",
                    "24.4",
                    List.of(),
                    List.of(),
                    List.of()));
        case CipKnowledgePackIndexLoader.PIPELINE_COMPATIBILITY_REPORT_FILE ->
            objectMapper.writeValueAsBytes(
                new org.qubership.integration.platform.ai.compiler.pipeline
                        .PipelineCompatibilityReport(
                    1,
                    null,
                    "digest",
                    org.qubership.integration.platform.ai.compiler.pipeline.PipelineChangeClass
                            .BOOTSTRAP,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    true,
                    List.of()));
        default -> throw new IllegalArgumentException("Unexpected test resource: " + fileName);
      };
    }
  }
}
