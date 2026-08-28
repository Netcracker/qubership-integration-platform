package org.qubership.integration.platform.ai.integration.catalog.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

class CatalogRankerTest {

  private static final CatalogRestClient.SystemDto OM =
      new CatalogRestClient.SystemDto(
          "sys-om", "om-order-lifecycle-manager-WFMS", "INTERNAL", "kafka");

  private static final CatalogRestClient.SystemDto SALESFORCE =
      new CatalogRestClient.SystemDto("sys-sf", "Salesforce WFM", "EXTERNAL", "http");

  private static final CatalogRestClient.OperationDto ON_TASK_RESULT =
      new CatalogRestClient.OperationDto(
          "op-result", "onTaskResult", "subscribe", "env05-bss.order.command.queue", "model-om");

  private static final CatalogRestClient.OperationDto ON_TASK_START =
      new CatalogRestClient.OperationDto(
          "op-start", "onTaskStart", "publish", "env05-bss.task.start", "model-om");

  private static final CatalogRestClient.OperationDto CREATE_TASK =
      new CatalogRestClient.OperationDto(
          "op-create", "createTask", "POST", "/sobjects/Task", "model-sf");

  private static CatalogQuery query(
      String systemHint, String method, String path, String operationHint) {
    return new CatalogQuery(systemHint, null, null, method, path, operationHint, null);
  }

  @Test
  @DisplayName("an operation name matches even when the service name never could")
  void matchesThroughADisagreeingServiceName() {
    CatalogQuery query = query("om-order-lifecycle-manager async", null, null, "onTaskResult");

    int score = CatalogRanker.score(query, OM, ON_TASK_RESULT);

    // The old lookup dropped this service before reading operations: neither name contains the
    // other. Three of the hint's four tokens do appear, and the operation name matches outright.
    assertTrue(score >= CatalogRanker.THRESHOLD, "score " + score);
    assertEquals(
        CatalogRanker.OPERATION_IDENTITY + CatalogRanker.SYSTEM_NAME_OVERLAP * 3 / 4, score);
  }

  @Test
  @DisplayName("a sibling operation of the same service stays below the threshold")
  void rejectsSiblingOperation() {
    CatalogQuery query = query("om-order-lifecycle-manager async", null, null, "onTaskResult");

    assertTrue(CatalogRanker.score(query, OM, ON_TASK_START) < CatalogRanker.THRESHOLD);
  }

  @Test
  @DisplayName("method and path identify an operation whose name matches nothing")
  void matchesOnMethodAndPath() {
    CatalogQuery query = query("Salesforce WFM", "POST", "/sobjects/Task", "POST /sobjects/Task");

    assertEquals(
        CatalogRanker.OPERATION_IDENTITY + CatalogRanker.SYSTEM_NAME_OVERLAP,
        CatalogRanker.score(query, SALESFORCE, CREATE_TASK));
  }

  @Test
  @DisplayName("a service name alone never reaches the threshold")
  void serviceNameAloneIsNotAMatch() {
    CatalogQuery query = query("Salesforce WFM", null, null, "deleteAccount");

    assertTrue(CatalogRanker.score(query, SALESFORCE, CREATE_TASK) < CatalogRanker.THRESHOLD);
  }

  @Test
  @DisplayName("the protocol separates two services that offer the same operation")
  void protocolBreaksATie() {
    CatalogQuery kafka =
        new CatalogQuery("order manager", null, "kafka", null, null, "onTaskResult", null);
    CatalogQuery http =
        new CatalogQuery("order manager", null, "http", null, null, "onTaskResult", null);

    assertEquals(
        CatalogRanker.score(http, OM, ON_TASK_RESULT) + CatalogRanker.PROTOCOL,
        CatalogRanker.score(kafka, OM, ON_TASK_RESULT));
  }

  @Test
  @DisplayName("a trailing slash does not break a path match")
  void ignoresTrailingSlash() {
    CatalogQuery query = query("Salesforce WFM", "POST", "/sobjects/Task/", "createTask");

    assertTrue(
        CatalogRanker.score(query, SALESFORCE, CREATE_TASK) >= CatalogRanker.OPERATION_IDENTITY);
  }
}
