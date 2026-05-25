package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport.StageFailure;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport.StageOutcome;

import java.util.ArrayList;
import java.util.List;

/** Factory helpers for {@link CreateElementsByJsonReport} stage failures and outcomes. */
public final class ReportFactories {

  private ReportFactories() {}

  public static StageFailure skeleton(
      String clientId, String type, String parentElementId, String reason) {
    StageFailure sf = new StageFailure();
    sf.clientId = clientId;
    sf.type = type;
    sf.parentElementId = parentElementId;
    sf.reason = reason;
    return sf;
  }

  public static StageFailure connection(String fromClientId, String toClientId, String reason) {
    StageFailure sf = new StageFailure();
    sf.fromClientId = fromClientId;
    sf.toClientId = toClientId;
    sf.reason = reason;
    return sf;
  }

  public static StageFailure property(String clientId, String elementId, String reason) {
    StageFailure sf = new StageFailure();
    sf.clientId = clientId;
    sf.elementId = elementId;
    sf.reason = reason;
    return sf;
  }

  public static StageOutcome finalizeStage(List<StageFailure> failures) {
    StageOutcome o = new StageOutcome();
    o.failures = failures == null ? new ArrayList<>() : new ArrayList<>(failures);
    o.ok = o.failures.isEmpty();
    return o;
  }
}
