package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class ScriptExecutionAuthorityTest {
  private static final String BODY = "  exchange.in.body = [Subject: 'author value']\nreturn exchange.in.body\n\n";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void supplementaryNameAndSuccessorFactsShareOneExactSuppliedBody() {
    var facts = ScriptAuthorityTestFixtures.suppliedFactsWithSupplementaryMetadata("initializer", BODY);
    var brief = brief().withFacts(facts);

    assertNotEquals(facts.getFirst().elementIntent(), facts.getLast().elementIntent());
    assertEquals(Optional.of(BODY), ScriptExecutionAuthority.declaredSuppliedBody(brief, "initializer"));
    assertEquals(facts, brief.facts());
  }

  @ParameterizedTest
  @ValueSource(strings = {"name-and-successor", "body-only"})
  void eitherSupplementaryFactAloneAdmitsTheSameExactBody(String factShape) {
    var facts = ScriptAuthorityTestFixtures.suppliedFactsWithSupplementaryMetadata("initializer", BODY);
    assertEquals(Optional.of(BODY), ScriptExecutionAuthority.declaredSuppliedBody(
        brief().withFacts(List.of(facts.get("name-and-successor".equals(factShape) ? 0 : 1))), "initializer"), "factShape=" + factShape);
  }

  @Test
  void repeatedFullIntentAlsoSharesOneExactSuppliedBody() {
    var fact = ScriptAuthorityTestFixtures.suppliedFact("initializer", BODY);
    assertEquals(Optional.of(BODY), ScriptExecutionAuthority.declaredSuppliedBody(
        brief().withFacts(List.of(fact, fact)), "initializer"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"authority", "elementRef", "propertyKey", "bodyKind", "sourceGeneration",
      "sourceTurnId", "startOffset", "endOffset", "encoding"})
  void disagreementInAnyCompleteBindingFieldRejectsTheSecondFact(String field) throws Exception {
    var brief = duplicateBrief();
    ObjectNode binding = (ObjectNode) brief.withArray("facts").get(1).path("elementIntent").path("bodyBinding");
    switch (field) {
      case "authority" -> binding.put(field, "GENERATED_CANDIDATE");
      case "elementRef" -> binding.put(field, "another-owner");
      case "propertyKey" -> binding.put(field, "another-property");
      case "bodyKind" -> binding.putNull(field);
      case "startOffset" -> binding.put(field, 1);
      case "endOffset" -> binding.put(field, BODY.length() + 1);
      case "encoding" -> binding.put(field, "JSON_STRING");
      default -> binding.put(field, "another-source");
    }
    assertEquals(Optional.empty(), ScriptExecutionAuthority.declaredSuppliedBody(
        JSON.treeToValue(brief, RequirementBrief.class), "initializer"), "field=" + field);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missingBinding", "blankGeneration", "blankTurn", "negativeStart", "emptySpan",
      "missingEncoding", "blankBody", "changedBody", "duplicateScriptProperty"})
  void invalidSecondIntentCannotBorrowTheFirstFactsValidAuthority(String defect) throws Exception {
    var brief = duplicateBrief();
    ObjectNode intent = (ObjectNode) brief.withArray("facts").get(1).path("elementIntent");
    ObjectNode binding = (ObjectNode) intent.path("bodyBinding");
    switch (defect) {
      case "missingBinding" -> intent.putNull("bodyBinding");
      case "blankGeneration" -> binding.put("sourceGeneration", " ");
      case "blankTurn" -> binding.put("sourceTurnId", " ");
      case "negativeStart" -> binding.put("startOffset", -1);
      case "emptySpan" -> binding.put("endOffset", 0);
      case "missingEncoding" -> binding.putNull("encoding");
      case "blankBody" -> ((ObjectNode) intent.withArray("requestedProperties").get(0)).put("value", " ");
      case "changedBody" -> ((ObjectNode) intent.withArray("requestedProperties").get(0)).put("value", BODY + " ");
      case "duplicateScriptProperty" -> intent.withArray("requestedProperties").add(
          intent.withArray("requestedProperties").get(0).deepCopy());
      default -> throw new AssertionError(defect);
    }
    assertEquals(Optional.empty(), ScriptExecutionAuthority.declaredSuppliedBody(
        JSON.treeToValue(brief, RequirementBrief.class), "initializer"), "defect=" + defect);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missingBinding", "blankTurn", "foreignOwner", "sourceOffsets", "literalBody"})
  void rejectsInvalidGeneratedBehaviorAuthority(String defect) throws Exception {
    ObjectNode brief = JSON.valueToTree(brief().withFacts(List.of(
        ScriptAuthorityTestFixtures.generatedBehavior("initializer", "Return Hello world"))));
    ObjectNode intent = (ObjectNode) brief.withArray("facts").get(0).path("elementIntent");
    ObjectNode binding = (ObjectNode) intent.path("bodyBinding");
    switch (defect) {
      case "missingBinding" -> intent.putNull("bodyBinding");
      case "blankTurn" -> binding.put("sourceTurnId", " ");
      case "foreignOwner" -> binding.put("elementRef", "another-script");
      case "sourceOffsets" -> binding.put("startOffset", 0);
      case "literalBody" -> intent.withArray("requestedProperties").addObject().put("key", "script").put("value", "return 1");
      default -> throw new AssertionError(defect);
    }

    assertEquals(Optional.empty(), ScriptExecutionAuthority.declaredGeneratedBehavior(
        JSON.treeToValue(brief, RequirementBrief.class), "initializer"), defect);
  }

  private static ObjectNode duplicateBrief() {
    return JSON.valueToTree(brief().withFacts(
        ScriptAuthorityTestFixtures.suppliedFactsWithSupplementaryMetadata("initializer", BODY)));
  }

  private static RequirementBrief brief() {
    return new RequirementBrief("Use the supplied initializer", List.of(), List.of(), List.of(), List.of(), "");
  }
}
