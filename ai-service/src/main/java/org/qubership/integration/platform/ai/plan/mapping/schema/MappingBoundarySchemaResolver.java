package org.qubership.integration.platform.ai.plan.mapping.schema;

import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;

/** Resolves persisted schema artifacts for one mapping intent's source and target ports. */
public interface MappingBoundarySchemaResolver {

  MappingBoundarySchemas resolve(
      ChainSemanticRevision revision,
      List<ResolvedServiceCallBinding> bindings,
      MappingIntent intent,
      Map<String, MappingEnvelope> envelopesByTransformNodeId);
}
