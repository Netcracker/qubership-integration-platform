package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract.ElementContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContractRepository;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorException;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackManifest;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;

/**
 * Stores one validated semantic revision in {@link ProductCapabilityCaptureContext}. Duplicate
 * capture fails closed and does not replace the stored candidate.
 */
@ApplicationScoped
public class ChainSemanticCaptureTool {

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Chain semantic revision already captured. Do not call captureChainSemanticRevision again;"
          + " finish this turn without further tool calls.";

  static final String CAPTURED_MESSAGE =
      "Chain semantic revision captured. Do not call captureChainSemanticRevision again;"
          + " finish this turn without further tool calls.";

  private final ChainSemanticRevisionValidator validator;
  private final CompilerContractRepository contractRepository;
  private final QipKnowledgePackRepository knowledgePackRepository;
  private final CatalogElementDescriptorLoader descriptorLoader;

  @Inject
  public ChainSemanticCaptureTool(
      ChainSemanticRevisionValidator validator,
      CompilerContractRepository contractRepository,
      QipKnowledgePackRepository knowledgePackRepository,
      CatalogElementDescriptorLoader descriptorLoader) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.contractRepository = Objects.requireNonNull(contractRepository, "contractRepository");
    this.knowledgePackRepository =
        Objects.requireNonNull(knowledgePackRepository, "knowledgePackRepository");
    this.descriptorLoader = Objects.requireNonNull(descriptorLoader, "descriptorLoader");
  }

  @Tool("""
      Capture the typed chain semantic revision for this design-input turn.
      Do not pass conversationId. The server binds capture to the current design session.
      Copy entryPointId, sourceFactIds, and serviceCallId from the approved requirement brief.
      Do not mint occurrence ids. Call this once, then finish the turn.""")
  public String captureChainSemanticRevision(ChainSemanticRevision revision) {
    if (revision == null) {
      return "revision is required";
    }
    var binding = ProductCapabilityCaptureContext.current().orElse(null);
    if (binding == null
        || binding.mode() != ProductCapabilityCaptureContext.Mode.DESIGN) {
      return "Design capture is not bound. Call captureChainSemanticRevision only during"
          + " design-input.";
    }
    if (ProductCapabilityCaptureContext.semanticCandidate().isPresent()) {
      return DUPLICATE_CAPTURE_MESSAGE;
    }
    RequirementBrief brief = binding.approvedBrief();
    if (brief == null) {
      return "Approved requirement brief is required before capturing a semantic revision";
    }
    String ownershipError = ownershipError(revision, brief);
    if (ownershipError != null) {
      return ownershipError;
    }
    CompilerContract contract = contractRepository.require(CompilerContract.V1);
    try {
      validator.validate(revision, contract);
    } catch (IllegalArgumentException ex) {
      return ex.getMessage();
    }
    String preflightError = preflight(revision, contract);
    if (preflightError != null) {
      return preflightError;
    }
    ProductCapabilityCaptureContext.offerSemantic(revision);
    return CAPTURED_MESSAGE;
  }

  private static String ownershipError(ChainSemanticRevision revision, RequirementBrief brief) {
    Set<String> entryPointIds = new HashSet<>();
    for (RequirementEntryPoint entryPoint : brief.entryPoints()) {
      if (entryPoint != null && !entryPoint.entryPointId().isBlank()) {
        entryPointIds.add(entryPoint.entryPointId());
      }
    }
    for (SemanticEntryPoint entry : revision.entryPoints()) {
      if (!entryPointIds.contains(entry.entryPointId())) {
        return "Entry point '"
            + entry.entryPointId()
            + "' is not in the approved requirement brief";
      }
    }
    Set<String> factIds = new HashSet<>();
    for (RequirementFact fact : brief.facts()) {
      if (fact != null && fact.sourceFactId() != null && !fact.sourceFactId().isBlank()) {
        factIds.add(fact.sourceFactId());
      }
    }
    for (SemanticEntryPoint entry : revision.entryPoints()) {
      String missing = missingFact(entry.provenance(), factIds);
      if (missing != null) {
        return missing;
      }
    }
    for (SemanticNode node : revision.nodes()) {
      String missing = missingFact(node.provenance(), factIds);
      if (missing != null) {
        return missing;
      }
    }
    Set<String> serviceCallIds = new HashSet<>();
    for (RequirementServiceCall call : brief.serviceCalls()) {
      if (call != null && !call.serviceCallId().isBlank()) {
        serviceCallIds.add(call.serviceCallId());
      }
    }
    for (SemanticNode node : revision.nodes()) {
      if (node instanceof SemanticNode.ServiceCall call
          && !serviceCallIds.contains(call.serviceCallId())) {
        return "serviceCallId '"
            + call.serviceCallId()
            + "' is not in the approved requirement brief";
      }
    }
    return null;
  }

  private static String missingFact(SemanticProvenance provenance, Set<String> factIds) {
    if (provenance == null) {
      return null;
    }
    for (String sourceFactId : provenance.sourceFactIds()) {
      if (!factIds.contains(sourceFactId)) {
        return "Provenance sourceFactId '"
            + sourceFactId
            + "' is not in the approved requirement brief";
      }
    }
    return null;
  }

  private String preflight(ChainSemanticRevision revision, CompilerContract contract) {
    QipKnowledgePackManifest manifest = knowledgePackRepository.loadManifest();
    for (String addonId : contract.requiredAddons()) {
      if (!manifest.addonSha256().containsKey(addonId)) {
        return "Required compiler addon is missing: " + addonId;
      }
    }
    for (String fragment : contract.requiredKnowledgeFragments()) {
      if (!fragmentPresent(manifest, fragment)) {
        return "Required knowledge fragment is missing: " + fragment;
      }
    }
    Set<String> types = new HashSet<>();
    for (SemanticNode node : revision.nodes()) {
      types.add(elementType(node));
    }
    for (String type : types) {
      ElementContract element = contract.elements().get(type);
      if (element == null || element.runtimeDescriptor() == null) {
        continue;
      }
      String descriptorType = element.runtimeDescriptor().type();
      if (descriptorType == null || descriptorType.isBlank()) {
        descriptorType = type;
      }
      try {
        descriptorLoader.load(descriptorType);
      } catch (CatalogElementDescriptorException ex) {
        return "Required runtime descriptor is missing: " + descriptorType;
      }
    }
    return null;
  }

  private static boolean fragmentPresent(QipKnowledgePackManifest manifest, String fragment) {
    for (String path : manifest.fileChecksums().keySet()) {
      if (path.equals(fragment) || path.contains(fragment)) {
        return true;
      }
    }
    return false;
  }

  private static String elementType(SemanticNode node) {
    return switch (node) {
      case SemanticNode.Trigger trigger -> trigger.capabilityKey();
      case SemanticNode.ServiceCall ignored -> "service-call";
      case SemanticNode.Operation operation -> operation.elementType();
    };
  }
}
