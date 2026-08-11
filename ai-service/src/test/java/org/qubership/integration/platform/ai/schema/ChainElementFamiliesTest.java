package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ChainElementFamiliesTest {

  private final ChainElementCatalog catalog = new ChainElementCatalog(new ObjectMapper());

  @Test
  void triggerFamilyIsExplicitNotSuffixBased() {
    assertTrue(ChainElementFamilies.isTrigger("http-trigger"));
    assertTrue(ChainElementFamilies.isTrigger("quartz-scheduler"));
    assertFalse(ChainElementFamilies.isTrigger("custom-trigger"));
    assertFalse(ChainElementFamilies.isTrigger("custom-trigger-2"));
  }

  @Test
  void familyMembersExistInSchemaCatalog() {
    allFamilyTypes().forEach(type -> assertTrue(catalog.isKnown(type), type));
  }

  @Test
  void currentFamiliesDoNotContainDeprecatedLoopExpression() {
    assertFalse(ChainElementFamilies.LOOP.contains("loop-expression"));
    assertTrue(catalog.isDeprecated("loop-expression"));
  }

  private static Stream<String> allFamilyTypes() {
    return Stream.of(
            ChainElementFamilies.TRIGGERS,
            ChainElementFamilies.ROUTING,
            ChainElementFamilies.TRY_CATCH,
            ChainElementFamilies.TRY_CATCH_DEPRECATED,
            ChainElementFamilies.LOOP,
            ChainElementFamilies.PARALLEL,
            ChainElementFamilies.CHAIN_CALL)
        .flatMap(Set::stream)
        .distinct();
  }
}
