package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;

/** Projects a validated semantic revision into a chain plan graph. */
public interface ChainSemanticGraphCompiler {

  ChainPlanGraph compile(
      ChainSemanticRevision revision,
      CompilerContract contract,
      List<CatalogBindingResolution> bindings);
}
