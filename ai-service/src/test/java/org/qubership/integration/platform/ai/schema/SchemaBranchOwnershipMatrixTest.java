package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.GeneratorPatchRegressionHarness;

class SchemaBranchOwnershipMatrixTest {

  @Test
  void everyInScopeBranchRequiredKeyHasAnOwner() {
    ObjectMapper objectMapper = new ObjectMapper();
    DeterministicElementSchemaService schemaService =
        DeterministicElementSchemaService.createForUnitTests(objectMapper);
    Map<String, Set<String>> owned =
        GeneratorPatchRegressionHarness.pinnedOwnershipPropertiesByElementType();

    List<String> violations = new ArrayList<>();
    for (String type : ChainElementFamilies.classifiedTriggerAndSenderTypes()) {
      if (ChainElementFamilies.bindingMode(type)
          == ChainElementFamilies.BindingMode.UNSUPPORTED_IN_CREATE) {
        continue;
      }
      if (!schemaService.hasElementSchema(type)) {
        continue;
      }
      ElementPropertiesSchemaModel model = schemaService.elementPropertiesSchemaModel(type);
      Set<String> needed = new TreeSet<>(model.unconditionalRequired());
      addAllBranchRequiredKeys(needed, model.conditionalBranches());
      Set<String> unconditionalDefaults = unconditionalDefaultKeys(schemaService, type);
      unconditionalDefaults.removeAll(discriminatorKeys(model.conditionalBranches()));
      needed.removeAll(unconditionalDefaults);
      needed.removeAll(owned.getOrDefault(type, Set.of()));
      if (!needed.isEmpty()) {
        violations.add(type + " missing owners for " + needed);
      }
    }
    assertEquals(List.of(), violations);
  }

  private static void addAllBranchRequiredKeys(
      Set<String> keys, List<SchemaIfThenBranch> branches) {
    for (SchemaIfThenBranch branch : branches) {
      keys.addAll(branch.thenRequired());
      keys.addAll(branch.elseRequired());
      addAllBranchRequiredKeys(keys, branch.nestedThen());
      addAllBranchRequiredKeys(keys, branch.nestedElse());
    }
  }

  private static Set<String> discriminatorKeys(List<SchemaIfThenBranch> branches) {
    Set<String> keys = new LinkedHashSet<>();
    for (SchemaIfThenBranch branch : branches) {
      keys.add(branch.discriminatorKey());
      keys.addAll(discriminatorKeys(branch.nestedThen()));
      keys.addAll(discriminatorKeys(branch.nestedElse()));
    }
    return keys;
  }

  private static Set<String> unconditionalDefaultKeys(
      DeterministicElementSchemaService schemaService, String elementType) {
    Set<String> keys = new LinkedHashSet<>();
    for (PlanProperty property :
        schemaService.withUnconditionalSchemaDefaults(elementType, List.of())) {
      if (property != null && property.key() != null) {
        keys.add(property.key());
      }
    }
    return keys;
  }
}
