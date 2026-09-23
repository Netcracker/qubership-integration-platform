package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract.ElementContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContractRepository;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorException;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryFaultInjector;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackManifest;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;

/**
 * Stores one validated semantic revision in {@link ProductCapabilityCaptureContext}. The model
 * sends a tolerant {@link ChainSemanticCapture}; {@link ChainSemanticCaptureAdapter} projects it
 * onto the canonical revision, which is then validated and preflighted. Duplicate capture fails
 * closed and does not replace the stored candidate.
 */
@ApplicationScoped
public class ChainSemanticCaptureTool {

  private static final Logger LOG = Logger.getLogger(ChainSemanticCaptureTool.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public record CaptureIssue(String code, String path, String message) {}

  public record CaptureResult(
      boolean accepted, boolean changed, String nextAction,
      List<CaptureIssue> issues, String message) {}

  public static final String TOOL_NAME = "captureChainSemanticRevision";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Chain semantic revision already captured. Do not call captureChainSemanticRevision again;"
          + " finish this turn without further tool calls.";

  static final String CAPTURED_MESSAGE =
      "Chain semantic revision captured. Do not call captureChainSemanticRevision again;"
          + " finish this turn without further tool calls.";

  private final ChainSemanticCaptureAdapter adapter;
  private final ChainSemanticRevisionValidator validator;
  private final CompilerContractRepository contractRepository;
  private final QipKnowledgePackRepository knowledgePackRepository;
  private final CatalogElementDescriptorLoader descriptorLoader;
  private final E2eRecoveryFaultInjector recoveryFaultInjector;

  @Inject
  public ChainSemanticCaptureTool(
      ChainSemanticCaptureAdapter adapter,
      ChainSemanticRevisionValidator validator,
      CompilerContractRepository contractRepository,
      QipKnowledgePackRepository knowledgePackRepository,
      CatalogElementDescriptorLoader descriptorLoader,
      E2eRecoveryFaultInjector recoveryFaultInjector) {
    this.adapter = Objects.requireNonNull(adapter, "adapter");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.contractRepository = Objects.requireNonNull(contractRepository, "contractRepository");
    this.knowledgePackRepository =
        Objects.requireNonNull(knowledgePackRepository, "knowledgePackRepository");
    this.descriptorLoader = Objects.requireNonNull(descriptorLoader, "descriptorLoader");
    this.recoveryFaultInjector = Objects.requireNonNull(recoveryFaultInjector, "recoveryFaultInjector");
  }

  public ChainSemanticCaptureTool(
      ChainSemanticCaptureAdapter adapter,
      ChainSemanticRevisionValidator validator,
      CompilerContractRepository contractRepository,
      QipKnowledgePackRepository knowledgePackRepository,
      CatalogElementDescriptorLoader descriptorLoader) {
    this(adapter, validator, contractRepository, knowledgePackRepository, descriptorLoader,
        new E2eRecoveryFaultInjector("", ""));
  }

  @Tool(value = """
      Capture the chain topology for this design-input turn.
      Do not pass conversationId. The server binds capture to the current design session.
      Copy sourceFactIds and mappingIntentId from the approved requirement brief. Do not mint
      occurrence ids. External interaction anchors are server-owned. Reference these node ids
      from edges, but do not list them under operations except to attach extra sourceFactIds
      to a native outbound node (direct sender or chain-call-2). Do not add another node with
      that elementType. Preserve every approved business transition. You may insert internal
      processing nodes between its source and target, but you may not reverse, omit, or add
      an external interaction transition.
      The server owns every id it can derive: leave out revision ids, edge ids, schema versions,
      and compiler contract versions, and leave out catalog values it reads from the brief.
      List each internal node you do author under operations, and each control-flow region
      under the list that matches its kind; omit the region lists when the chain is linear.
      Submit one candidate per runtime attempt, then finish the turn. If rejected, return
      the findings; the runtime supplies them on a bounded regeneration attempt.""",
      returnBehavior = ReturnBehavior.IMMEDIATE)
  public String captureChainSemanticRevision(ChainSemanticCapture capture) {
    long startMs = System.currentTimeMillis();
    String conversationId = ToolSession.resolveConversationId();
    ToolTraceLog.logToolInvoke(LOG, TOOL_NAME, conversationId, shape(capture));
    String result = encode(capture(capture, conversationId));
    ToolTraceLog.logToolComplete(
        LOG, TOOL_NAME, conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }

  /** Counts only, so a rejected capture is diagnosable without logging brief content. */
  private static String shape(ChainSemanticCapture capture) {
    if (capture == null) {
      return "null";
    }
    return "operations=%d regions=%d edges=%d"
        .formatted(
            capture.operations().size(),
            capture.sequenceRegions().size()
                + capture.conditionRegions().size()
                + capture.splitRegions().size()
                + capture.loopRegions().size()
                + capture.retryRegions().size()
                + capture.errorScopeRegions().size(),
            capture.edges().size());
  }

  private CaptureResult capture(ChainSemanticCapture capture, String conversationId) {
    if (capture == null) {
      return rejected("MISSING_FIELD", "/capture", "capture is required", true);
    }
    // LangChain4j runs this tool on a pooled worker thread that never called bindDesign, and that
    // thread may still carry an earlier stage's binding, so resolve by conversation id.
    var binding = ProductCapabilityCaptureContext.designBinding(conversationId).orElse(null);
    if (binding == null) {
      return rejected("DESIGN_SESSION_NOT_FOUND", "/capture",
          "Design capture is not bound. Call captureChainSemanticRevision only during design-input.",
          true);
    }
    if (binding.semanticCandidate().get() != null) {
      return rejected("DUPLICATE_CAPTURE", "/capture", DUPLICATE_CAPTURE_MESSAGE, true);
    }
    RequirementBrief brief = binding.approvedBrief();
    if (brief == null) {
      ProductCapabilityCaptureContext.offerSemanticRejection(binding,
          "Approved requirement brief is required before capturing a semantic revision", true);
      return rejected("APPROVED_BRIEF_REQUIRED", "/capture",
          "Approved requirement brief is required before capturing a semantic revision", true);
    }
    CompilerContract contract = contractRepository.require(CompilerContract.V1);
    ChainSemanticRevision revision;
    try {
      revision = adapter.adapt(capture, binding.runId(), brief, contract);
      validator.validate(revision, contract, brief);
    } catch (IllegalArgumentException ex) {
      ProductCapabilityCaptureContext.offerSemanticRejection(binding, ex.getMessage());
      return rejected("DESIGN_CONTRACT_VIOLATION", "/capture", ex.getMessage(), false);
    }
    if (recoveryFaultInjector.next(binding.runId(), capture.chainIdentity(), "design-input")
        .filter(code -> code == RecoveryCauseCode.CONTRACT_SHAPE).isPresent()) {
      String injected = "Injected E2E design capture rejection.";
      ProductCapabilityCaptureContext.offerSemanticRejection(binding, injected);
      return rejected("INJECTED_DESIGN_REJECTION", "/capture", injected, false);
    }
    String preflightError = preflight(revision, contract);
    if (preflightError != null) {
      ProductCapabilityCaptureContext.offerSemanticRejection(binding, preflightError, true);
      return rejected("RUNTIME_CONFIGURATION_ERROR", "/capture", preflightError, true);
    }
    ProductCapabilityCaptureContext.offerSemantic(binding, revision);
    return new CaptureResult(true, true, "HANDOFF", List.of(), CAPTURED_MESSAGE);
  }

  private static CaptureResult rejected(
      String code, String path, String message, boolean terminal) {
    return new CaptureResult(false, false, terminal ? "STOP" : "REPAIR_CAPTURE",
        List.of(new CaptureIssue(code, path, message)), message);
  }

  /** Returns the same outcome shape when arguments fail before the tool method can run. */
  public static String rejectArguments(String conversationId, CaptureIssue issue) {
    var binding = ProductCapabilityCaptureContext.designBinding(conversationId).orElse(null);
    if (binding == null) {
      return encode(rejected("DESIGN_SESSION_NOT_FOUND", "/capture",
          "Design capture is not bound.", true));
    }
    ProductCapabilityCaptureContext.offerSemanticRejection(
        binding, issue.code() + " at " + issue.path() + ": " + issue.message());
    return encode(new CaptureResult(false, false, "REPAIR_CAPTURE", List.of(issue), issue.message()));
  }

  private static String encode(CaptureResult result) {
    try {
      return MAPPER.writeValueAsString(result);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Cannot serialize design capture result", error);
    }
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
    String fileName = knowledgeFileName(fragment);
    for (String path : manifest.fileChecksums().keySet()) {
      if (fileName.equals(path) || fileName.equals(lastSegment(path))) {
        return true;
      }
    }
    return false;
  }

  private static String knowledgeFileName(String fragment) {
    return switch (fragment) {
      case "validation-rules" -> "validation-rules.yaml";
      case "generator-contracts" -> "GENERATOR_CONTRACTS.md";
      case "generator-rule-mapping" -> "generator-rule-mapping.md";
      default -> fragment;
    };
  }

  private static String lastSegment(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static String elementType(SemanticNode node) {
    return switch (node) {
      case SemanticNode.Trigger trigger -> trigger.capabilityKey();
      case SemanticNode.ServiceCall ignored -> "service-call";
      case SemanticNode.Operation operation -> operation.elementType();
    };
  }
}
