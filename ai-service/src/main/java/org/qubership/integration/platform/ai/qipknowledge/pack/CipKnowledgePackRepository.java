package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.io.InputStream;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineCompatibilityReport;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageIndex;
import org.qubership.integration.platform.ai.productpipeline.packageindex.ProductPipelinePackageIndex;
import org.qubership.integration.platform.ai.qipknowledge.rag.CipKnowledgeRagIngestionManifest;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;
import org.qubership.integration.platform.ai.qipknowledge.support.ElementSupportMatrix;

/** Read-only access to pre-built QIP skill-pack indexes. */
public interface CipKnowledgePackRepository {
  CipKnowledgePackVersion activeVersion();

  CipKnowledgePackManifest loadManifest();

  CapabilityRegistry loadCapabilityRegistry();

  CipKnowledgeRagIngestionManifest loadRagIngestionManifest();

  CompilerGeneratorPolicy loadCompilerGeneratorPolicy();

  CompilerSkillCatalog loadCompilerSkillCatalog();

  CompilerGeneratorSpecIndex loadCompilerGeneratorSpecIndex();

  CompilerRuntimePackageIndex loadCompilerRuntimePackageIndex();

  CompilerPipelineIndex loadCompilerPipelineIndex();

  ElementSupportMatrix loadElementSupportMatrix();

  PipelineCompatibilityReport loadPipelineCompatibilityReport();

  List<UnsupportedCipKnowledgeItem> loadUnsupportedItems();

  List<String> loadRuntimePromotedSkillIds();

  ProductPipelinePackageIndex loadProductPipelinePackageIndex();

  /**
   * Relative paths under {@code product-pipelines/} for profile YAML files copied at pack build time
   * (for example {@code profiles/create-chain-v3.yaml}).
   */
  List<String> loadProductPipelineProfileResources();

  /**
   * Opens a resource under {@code product-pipelines/}, or returns {@code null} when absent.
   *
   * @param relativePath path relative to {@code product-pipelines/}, such as
   *     {@code artifact-schemas.yaml} or {@code rules/create-exclusions-v1.yaml}
   */
  InputStream openProductPipelineResource(String relativePath);
}
