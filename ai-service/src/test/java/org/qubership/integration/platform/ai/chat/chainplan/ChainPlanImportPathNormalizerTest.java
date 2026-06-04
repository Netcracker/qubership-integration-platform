package org.qubership.integration.platform.ai.chat.chainplan;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainPlanImportPathNormalizerTest {

  private static final String APIHUB_OP_ID =
      "serviceCatalogManagement-v4-serviceCatalogManagement-v4-serviceSpecification-_id_-get";

  @Test
  void normalizeMovesMisplacedApiHubOperationIdToImportPath() {
    ElementPlan node = new ElementPlan();
    node.setClientId("service-call-1");
    node.setType("service-call");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("integrationOperationId", APIHUB_OP_ID);
    props.put("integrationSystemId", "should-be-removed");
    node.setExpectedProperties(props);
    node.setApiHubPackageId("S.ActProv.SvcCat");
    node.setApiHubVersion("2026.1@1");
    node.setCatalogSystemName("Service Catalog");
    node.setCatalogSystemType("EXTERNAL");

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));

    ChainPlanImportPathNormalizer.normalize(plan);

    assertTrue(node.getImportRequired());
    assertEquals(APIHUB_OP_ID, node.getApiHubOperationId());
    assertNull(node.getExpectedProperties());
  }

  @Test
  void normalizeLiftsImportMetadataFromExpectedProperties() {
    ElementPlan node = new ElementPlan();
    node.setClientId("retrieve-service-specification");
    node.setType("service-call");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("importRequired", true);
    props.put("apiHubPackageId", "S.ActProv.SvcCat");
    props.put("apiHubVersion", "2026.1@1");
    props.put("apiHubOperationId", APIHUB_OP_ID);
    props.put("catalogSystemName", "Service Catalog Management");
    props.put("catalogSystemType", "EXTERNAL");
    node.setExpectedProperties(props);

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));

    ChainPlanImportPathNormalizer.normalize(plan);

    assertTrue(node.getImportRequired());
    assertEquals("S.ActProv.SvcCat", node.getApiHubPackageId());
    assertEquals("2026.1@1", node.getApiHubVersion());
    assertEquals(APIHUB_OP_ID, node.getApiHubOperationId());
    assertEquals("Service Catalog Management", node.getCatalogSystemName());
    assertEquals("EXTERNAL", node.getCatalogSystemType());
    assertNull(node.getExpectedProperties());
  }

  @Test
  void normalizeStripsCatalogBindingKeysFromImportRequiredRow() {
    ElementPlan node = new ElementPlan();
    node.setClientId("service-call-1");
    node.setType("service-call");
    node.setImportRequired(true);
    node.setApiHubPackageId("S.ActProv.SvcCat");
    node.setApiHubVersion("2026.1@1");
    node.setApiHubOperationId(APIHUB_OP_ID);
    node.setCatalogSystemName("Service Catalog");
    node.setCatalogSystemType("EXTERNAL");
    node.setExpectedProperties(
        Map.of("integrationOperationId", APIHUB_OP_ID, "retryCount", 3));

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(List.of(node));

    ChainPlanImportPathNormalizer.normalize(plan);

    assertEquals(3, node.getExpectedProperties().get("retryCount"));
    assertNull(node.getExpectedProperties().get("integrationOperationId"));
  }
}
