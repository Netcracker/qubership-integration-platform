package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.CatalogDependencyKeys;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.PlanTreeUtils;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateDependencyRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ConnectionPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class ChainConnectionsApplier {

  private static final Logger LOG = Logger.getLogger(ChainConnectionsApplier.class);

  private final CatalogRestClient catalogRestClient;

  @Inject
  public ChainConnectionsApplier(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = catalogRestClient;
  }

  public void applyConnectionsStage(
      String chainId, ChainImplementationPlan plan, CreateElementsByJsonReport report) {
    List<CreateElementsByJsonReport.StageFailure> failures = new ArrayList<>();
    List<ConnectionPlan> edges = collectAllConnections(plan);
    if (edges.isEmpty()) {
      report.stages.connections = ReportFactories.finalizeStage(failures);
      LOG.infof("stage connections: chainId=%s attempted=0 created=0 skipped=0 failed=0", chainId);
      return;
    }

    Set<String> existing = new HashSet<>();
    try {
      List<CatalogDependencyDto> deps = catalogRestClient.listDependencies(chainId);
      existing.addAll(CatalogDependencyKeys.edgeKeysFromDependencies(deps));
    } catch (Exception e) {
      LOG.warnf(e, "stage connections: listDependencies failed chainId=%s", chainId);
      failures.add(
          ReportFactories.connection(
              null,
              null,
              "listDependencies failed: " + CatalogRestSupport.describeExceptionForToolResult(e)));
      report.stages.connections = ReportFactories.finalizeStage(failures);
      return;
    }

    int attempted = 0;
    int created = 0;
    int skipped = 0;
    int failed = 0;

    for (ConnectionPlan edge : edges) {
      if (edge == null) {
        continue;
      }
      attempted++;
      String fromC = CatalogStrings.blankToNull(edge.getFromClientId());
      String toC = CatalogStrings.blankToNull(edge.getToClientId());
      if (fromC == null || toC == null) {
        failures.add(
            ReportFactories.connection(
                fromC, toC, "connection missing fromClientId or toClientId"));
        failed++;
        continue;
      }
      String fromId = report.clientIds.get(fromC);
      String toId = report.clientIds.get(toC);
      if (fromId == null || toId == null) {
        failures.add(
            ReportFactories.connection(
                fromC,
                toC,
                "unknown clientId in connection (missing catalog id after skeleton): fromClientId="
                    + fromC
                    + " resolvedFrom="
                    + fromId
                    + " toClientId="
                    + toC
                    + " resolvedTo="
                    + toId));
        failed++;
        continue;
      }
      String key = CatalogDependencyKeys.edgeKey(fromId, toId);
      if (existing.contains(key)) {
        skipped++;
        continue;
      }
      try {
        catalogRestClient.createConnection(
            chainId, new CatalogCreateDependencyRequest(fromId, toId));
        existing.add(key);
        created++;
      } catch (Exception e) {
        failures.add(
            ReportFactories.connection(
                fromC, toC, CatalogRestSupport.describeExceptionForToolResult(e)));
        failed++;
      }
    }

    report.stages.connections = ReportFactories.finalizeStage(failures);
    LOG.infof(
        "stage connections: chainId=%s attempted=%d created=%d skipped=%d failed=%d",
        chainId, attempted, created, skipped, failed);
  }

  public static List<ConnectionPlan> collectAllConnections(ChainImplementationPlan plan) {
    List<ConnectionPlan> out = new ArrayList<>();
    if (plan == null) {
      return out;
    }
    if (plan.getConnections() != null) {
      out.addAll(plan.getConnections());
    }
    PlanTreeUtils.preOrder(
        plan.getElements(),
        node -> {
          if (node.getConnections() != null) {
            out.addAll(node.getConnections());
          }
        });
    return dedupe(out);
  }

  static List<ConnectionPlan> dedupe(List<ConnectionPlan> edges) {
    if (edges == null || edges.isEmpty()) {
      return edges == null ? List.of() : new ArrayList<>();
    }
    Set<String> seen = new LinkedHashSet<>();
    List<ConnectionPlan> unique = new ArrayList<>();
    for (ConnectionPlan e : edges) {
      if (e == null) {
        continue;
      }
      String f = CatalogStrings.blankToNull(e.getFromClientId());
      String t = CatalogStrings.blankToNull(e.getToClientId());
      if (f == null || t == null) {
        unique.add(e);
        continue;
      }
      String k = f + "->" + t;
      if (seen.add(k)) {
        unique.add(e);
      }
    }
    return unique;
  }
}
