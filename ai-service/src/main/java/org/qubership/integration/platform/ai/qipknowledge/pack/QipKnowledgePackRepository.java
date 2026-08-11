package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineCompatibilityReport;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageIndex;
import org.qubership.integration.platform.ai.productpipeline.packageindex.ProductPipelinePackageIndex;
import org.qubership.integration.platform.ai.qipknowledge.rag.QipKnowledgeRagIngestionManifest;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;

/** Read-only access to pre-built QIP skill-pack indexes. */
public interface QipKnowledgePackRepository {
  QipKnowledgePackVersion activeVersion();

  QipKnowledgePackManifest loadManifest();

  CapabilityRegistry loadCapabilityRegistry();

  QipKnowledgeRagIngestionManifest loadRagIngestionManifest();

  CompilerGeneratorPolicy loadCompilerGeneratorPolicy();

  CompilerSkillCatalog loadCompilerSkillCatalog();

  CompilerGeneratorSpecIndex loadCompilerGeneratorSpecIndex();

  CompilerRuntimePackageIndex loadCompilerRuntimePackageIndex();

  CompilerPipelineIndex loadCompilerPipelineIndex();

  PipelineCompatibilityReport loadPipelineCompatibilityReport();

  List<UnsupportedQipKnowledgeItem> loadUnsupportedItems();

  List<String> loadRuntimePromotedSkillIds();

  ProductPipelinePackageIndex loadProductPipelinePackageIndex();
}
