package org.qubership.integration.platform.ai.chat.chainplan;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.chat.context.ConversationContextBuilder;
import org.qubership.integration.platform.ai.configuration.AppConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test double for {@link PlanApprovalGate} (no LLM). Enabled via {@code
 * %test.quarkus.arc.selected-alternatives}.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class TestPlanApprovalGate extends PlanApprovalGate {

  private static final AtomicBoolean APPROVE_NEXT = new AtomicBoolean(false);

  /**
   * Required for CDI normal-scope proxy; {@link #isPlanApproved} does not use superclass
   * dependencies.
   */
  public TestPlanApprovalGate() {
    super(null, null, null);
  }

  @Inject
  public TestPlanApprovalGate(
      PlanApprovalClassifier classifier,
      AppConfig appConfig,
      ConversationContextBuilder conversationContextBuilder) {
    super(classifier, appConfig, conversationContextBuilder);
  }

  public static void setApproveNext(boolean approve) {
    APPROVE_NEXT.set(approve);
  }

  public static void reset() {
    APPROVE_NEXT.set(false);
  }

  @Override
  public boolean isPlanApproved(String conversationId, String fullUserText) {
    if (vetoesApproval(extractIntent(fullUserText))) {
      return false;
    }
    return APPROVE_NEXT.get();
  }
}
