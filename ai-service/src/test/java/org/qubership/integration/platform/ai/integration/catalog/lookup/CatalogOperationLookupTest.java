package org.qubership.integration.platform.ai.integration.catalog.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;

class CatalogOperationLookupTest {

  private static final CatalogRestClient.SystemDto OM =
      new CatalogRestClient.SystemDto(
          "sys-om", "om-order-lifecycle-manager-WFMS", "INTERNAL", "kafka");

  private static final CatalogRestClient.SpecificationDto OM_SPEC =
      new CatalogRestClient.SpecificationDto("spec-om", "1.33", "sg-om", "sys-om");

  private static final CatalogRestClient.OperationDto ON_TASK_RESULT =
      new CatalogRestClient.OperationDto(
          "op-result", "onTaskResult", "subscribe", "env05-bss.order.command.queue", "spec-om");

  private static final CatalogRestClient.OperationDto ON_TASK_START =
      new CatalogRestClient.OperationDto(
          "op-start", "onTaskStart", "publish", "env05-bss.task.start", "spec-om");

  private static final CatalogRestClient.SystemDto SALESFORCE =
      new CatalogRestClient.SystemDto("sys-sf", "Salesforce WFM", "EXTERNAL", "http");

  private static final CatalogRestClient.SpecificationDto SF_SPEC =
      new CatalogRestClient.SpecificationDto("spec-sf", "1.0", "sg-sf", "sys-sf");

  private static final CatalogRestClient.OperationDto CREATE_TASK =
      new CatalogRestClient.OperationDto(
          "op-create", "createTask", "POST", "/sobjects/Task", "spec-sf");

  private static final CatalogRestClient.OperationDto GET_TOKEN =
      new CatalogRestClient.OperationDto(
          "op-token", "getSalesforceToken", "POST", "/oauth/token", "spec-sf");

  @Test
  @DisplayName("a spaced service hint still binds onTaskResult on the hyphenated catalog name")
  void spacedHintBindsHyphenatedOmService() {
    CatalogSystemFinder finder = mock(CatalogSystemFinder.class);
    CatalogSystemReadTool readTool = mock(CatalogSystemReadTool.class);
    CatalogQuery query =
        new CatalogQuery(
            "om-order-lifecycle-manager WFMS",
            "WFMS Create Work Order",
            "kafka",
            null,
            null,
            "onTaskResult",
            null);
    when(finder.narrow(query)).thenReturn(new CatalogSystemFinder.Narrowed.Systems(List.of(OM)));
    when(readTool.getApiSpecifications("sys-om")).thenReturn(List.of(OM_SPEC));
    when(readTool.listCatalogOperations(eq("spec-om"), eq("sys-om"), isNull()))
        .thenReturn(List.of(ON_TASK_RESULT, ON_TASK_START));

    CatalogLookupResult result = new CatalogOperationLookup(finder, readTool).resolve(query);

    CatalogLookupResult.Exact exact = assertInstanceOf(CatalogLookupResult.Exact.class, result);
    assertEquals("op-result", exact.match().integrationOperationId());
    assertEquals("onTaskResult", exact.match().operationName());
    assertEquals("sys-om", exact.match().systemId());
  }

  @Test
  @DisplayName("too many catalog services is not a catalog miss")
  void tooBroadIsNotNone() {
    CatalogSystemFinder finder = mock(CatalogSystemFinder.class);
    when(finder.narrow(any()))
        .thenReturn(new CatalogSystemFinder.Narrowed.TooBroad(80));

    CatalogLookupResult result =
        new CatalogOperationLookup(finder, mock(CatalogSystemReadTool.class))
            .resolve(new CatalogQuery("order", null, "kafka", null, null, "onTaskResult", null));

    CatalogLookupResult.TooBroad tooBroad =
        assertInstanceOf(CatalogLookupResult.TooBroad.class, result);
    assertEquals(80, tooBroad.candidateCount());
  }

  @Test
  @DisplayName("a payload command name on a known system lists catalog operations, not a miss")
  void knownSystemWithUnmatchedOperationIsAmbiguous() {
    CatalogSystemFinder finder = mock(CatalogSystemFinder.class);
    CatalogSystemReadTool readTool = mock(CatalogSystemReadTool.class);
    CatalogQuery query =
        new CatalogQuery(
            "om-order-lifecycle-manager-WFMS", null, null, null, null, "completeTask", null);
    when(finder.narrow(query)).thenReturn(new CatalogSystemFinder.Narrowed.Systems(List.of(OM)));
    when(readTool.getApiSpecifications("sys-om")).thenReturn(List.of(OM_SPEC));
    when(readTool.listCatalogOperations(eq("spec-om"), eq("sys-om"), isNull()))
        .thenReturn(List.of(ON_TASK_RESULT, ON_TASK_START));

    CatalogLookupResult result = new CatalogOperationLookup(finder, readTool).resolve(query);

    CatalogLookupResult.Ambiguous ambiguous =
        assertInstanceOf(CatalogLookupResult.Ambiguous.class, result);
    assertEquals(List.of("op-result", "op-start"), ambiguous.candidateIds());
  }

  @Test
  @DisplayName("tied createTask ops prefer the spec named like the catalog system")
  void tiedCreateTaskPrefersSpecNamedLikeTheSystem() {
    CatalogRestClient.SpecificationDto titleSpec =
        new CatalogRestClient.SpecificationDto(
            "spec-sf-title", "Salesforce WFM", "sg-sf-title", "sys-sf");
    CatalogRestClient.SpecificationDto filenameSpec =
        new CatalogRestClient.SpecificationDto(
            "spec-sf-file", "Salesforce WFM Specification", "sg-sf-file", "sys-sf");
    CatalogRestClient.OperationDto titleCreate =
        new CatalogRestClient.OperationDto(
            "op-create-title", "createTask", "POST", "/sobjects/Task", "spec-sf-title");
    CatalogRestClient.OperationDto fileCreate =
        new CatalogRestClient.OperationDto(
            "op-create-file", "createTask", "POST", "/sobjects/Task", "spec-sf-file");
    CatalogSystemFinder finder = mock(CatalogSystemFinder.class);
    CatalogSystemReadTool readTool = mock(CatalogSystemReadTool.class);
    CatalogQuery query =
        new CatalogQuery("Salesforce WFM", null, "http", null, null, "createTask", null);
    when(finder.narrow(query))
        .thenReturn(new CatalogSystemFinder.Narrowed.Systems(List.of(SALESFORCE)));
    when(readTool.getApiSpecifications("sys-sf")).thenReturn(List.of(filenameSpec, titleSpec));
    when(readTool.listCatalogOperations(eq("spec-sf-file"), eq("sys-sf"), isNull()))
        .thenReturn(List.of(fileCreate));
    when(readTool.listCatalogOperations(eq("spec-sf-title"), eq("sys-sf"), isNull()))
        .thenReturn(List.of(titleCreate));

    CatalogLookupResult result = new CatalogOperationLookup(finder, readTool).resolve(query);

    CatalogLookupResult.Exact exact = assertInstanceOf(CatalogLookupResult.Exact.class, result);
    assertEquals("op-create-title", exact.match().integrationOperationId());
    assertEquals("spec-sf-title", exact.match().specificationId());
  }

  @Test
  @DisplayName("a named tied createTask id binds Exact when spec names carry a version suffix")
  void namedTiedCreateTaskIdBindsExact() {
    CatalogRestClient.SpecificationDto titleSpec =
        new CatalogRestClient.SpecificationDto(
            "spec-sf-title", "Salesforce WFM-1.0.0", "sg-sf-title", "sys-sf");
    CatalogRestClient.SpecificationDto filenameSpec =
        new CatalogRestClient.SpecificationDto(
            "spec-sf-file", "Salesforce WFM Specification-1.0.0", "sg-sf-file", "sys-sf");
    String titleOpId =
        "80be9ebb-b528-48e1-8803-e355c1f109c1-Salesforce WFM-1.0.0-createTask";
    String fileOpId =
        "80be9ebb-b528-48e1-8803-e355c1f109c1-Salesforce WFM Specification-1.0.0-createTask";
    CatalogRestClient.OperationDto titleCreate =
        new CatalogRestClient.OperationDto(
            titleOpId, "createTask", "POST", "/sobjects/Task", "spec-sf-title");
    CatalogRestClient.OperationDto fileCreate =
        new CatalogRestClient.OperationDto(
            fileOpId, "createTask", "POST", "/sobjects/Task", "spec-sf-file");
    CatalogSystemFinder finder = mock(CatalogSystemFinder.class);
    CatalogSystemReadTool readTool = mock(CatalogSystemReadTool.class);
    CatalogQuery query =
        new CatalogQuery(
            "Salesforce WFM",
            null,
            "http",
            null,
            null,
            "createTask",
            null,
            List.of(titleOpId));
    when(finder.narrow(query))
        .thenReturn(new CatalogSystemFinder.Narrowed.Systems(List.of(SALESFORCE)));
    when(readTool.getApiSpecifications("sys-sf")).thenReturn(List.of(filenameSpec, titleSpec));
    when(readTool.listCatalogOperations(eq("spec-sf-file"), eq("sys-sf"), isNull()))
        .thenReturn(List.of(fileCreate));
    when(readTool.listCatalogOperations(eq("spec-sf-title"), eq("sys-sf"), isNull()))
        .thenReturn(List.of(titleCreate));

    CatalogLookupResult result = new CatalogOperationLookup(finder, readTool).resolve(query);

    CatalogLookupResult.Exact exact = assertInstanceOf(CatalogLookupResult.Exact.class, result);
    assertEquals(titleOpId, exact.match().integrationOperationId());
    assertEquals("spec-sf-title", exact.match().specificationId());
  }

  @Test
  @DisplayName("naming both tied createTask ids leaves the lookup Ambiguous")
  void namingBothTiedCreateTaskIdsStaysAmbiguous() {
    CatalogRestClient.SpecificationDto titleSpec =
        new CatalogRestClient.SpecificationDto(
            "spec-sf-title", "Salesforce WFM-1.0.0", "sg-sf-title", "sys-sf");
    CatalogRestClient.SpecificationDto filenameSpec =
        new CatalogRestClient.SpecificationDto(
            "spec-sf-file", "Salesforce WFM Specification-1.0.0", "sg-sf-file", "sys-sf");
    String titleOpId =
        "80be9ebb-b528-48e1-8803-e355c1f109c1-Salesforce WFM-1.0.0-createTask";
    String fileOpId =
        "80be9ebb-b528-48e1-8803-e355c1f109c1-Salesforce WFM Specification-1.0.0-createTask";
    CatalogRestClient.OperationDto titleCreate =
        new CatalogRestClient.OperationDto(
            titleOpId, "createTask", "POST", "/sobjects/Task", "spec-sf-title");
    CatalogRestClient.OperationDto fileCreate =
        new CatalogRestClient.OperationDto(
            fileOpId, "createTask", "POST", "/sobjects/Task", "spec-sf-file");
    CatalogSystemFinder finder = mock(CatalogSystemFinder.class);
    CatalogSystemReadTool readTool = mock(CatalogSystemReadTool.class);
    CatalogQuery query =
        new CatalogQuery(
            "Salesforce WFM",
            null,
            "http",
            null,
            null,
            "createTask",
            null,
            List.of(titleOpId + " or " + fileOpId));
    when(finder.narrow(query))
        .thenReturn(new CatalogSystemFinder.Narrowed.Systems(List.of(SALESFORCE)));
    when(readTool.getApiSpecifications("sys-sf")).thenReturn(List.of(filenameSpec, titleSpec));
    when(readTool.listCatalogOperations(eq("spec-sf-file"), eq("sys-sf"), isNull()))
        .thenReturn(List.of(fileCreate));
    when(readTool.listCatalogOperations(eq("spec-sf-title"), eq("sys-sf"), isNull()))
        .thenReturn(List.of(titleCreate));

    CatalogLookupResult result = new CatalogOperationLookup(finder, readTool).resolve(query);

    CatalogLookupResult.Ambiguous ambiguous =
        assertInstanceOf(CatalogLookupResult.Ambiguous.class, result);
    assertEquals(List.of(fileOpId, titleOpId), ambiguous.candidateIds());
  }

  @Test
  @DisplayName("a payload command name does not bind a partner op the same request already named")
  void unmatchedPayloadNameDoesNotBindNamedPartnerOperation() {
    CatalogSystemFinder finder = mock(CatalogSystemFinder.class);
    CatalogSystemReadTool readTool = mock(CatalogSystemReadTool.class);
    CatalogQuery query =
        new CatalogQuery(
            "Salesforce WFM",
            null,
            "http",
            null,
            null,
            "completeTask",
            null,
            List.of("createTask", "onTaskResult", "completeTask"));
    when(finder.narrow(query))
        .thenReturn(new CatalogSystemFinder.Narrowed.Systems(List.of(SALESFORCE)));
    when(readTool.getApiSpecifications("sys-sf")).thenReturn(List.of(SF_SPEC));
    when(readTool.listCatalogOperations(eq("spec-sf"), eq("sys-sf"), isNull()))
        .thenReturn(List.of(CREATE_TASK, GET_TOKEN));

    CatalogLookupResult result = new CatalogOperationLookup(finder, readTool).resolve(query);

    CatalogLookupResult.Ambiguous ambiguous =
        assertInstanceOf(CatalogLookupResult.Ambiguous.class, result);
    assertEquals(List.of("op-create", "op-token"), ambiguous.candidateIds());
  }
}
