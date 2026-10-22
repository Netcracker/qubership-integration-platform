package org.qubership.integration.platform.ai.llm.routing;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterHeuristicsTest {

  @Test
  void importInAChainNameDoesNotTurnCreationIntoStandaloneImport() {
    assertTrue(RouterHeuristics.tryFastResolve(
        "\u0421\u043e\u0437\u0434\u0430\u0439 \u0446\u0435\u043f\u043e\u0447\u043a\u0443 VERIFY-IMPORT-CREATE-20261017 \u0441 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u043d\u043e\u0439 OpenAPI \u0441\u043f\u0435\u0446\u0438\u0444\u0438\u043a\u0430\u0446\u0438\u0435\u0439").isEmpty());
  }

  @Test
  void importThisSpecificationRoutesToImportSpecification() {
    assertEquals(
        ScenarioType.IMPORT_SPECIFICATION,
        RouterHeuristics.tryFastResolve("import this specification").orElseThrow());
  }

  @Test
  void importApiSpecificationRoutesToImportSpecification() {
    assertTrue(
        RouterHeuristics.tryFastResolve("please import the OpenAPI specification into catalog")
            .filter(type -> type == ScenarioType.IMPORT_SPECIFICATION)
            .isPresent());
  }

  @Test
  void chainRequestMentioningImportLeavesTheIntentToTheClassifier() {
    String idsPaste =
        """
        I need to create a new chain. I have a design for it

        # Integration Design Specification (IDS)

        | Comments | Catalog import smoke test: import Geographic Site from APIHub into CIP catalog |

        #### Catalog companions (import from APIHub)

        | `*.specification.cip.yaml` | parentId → group id |
        """;

    assertTrue(RouterHeuristics.tryFastResolve(idsPaste).isEmpty());
  }

  @Test
  void importPlusIdsPasteWithGatherOnlyDoesNotMisrouteToCreateDesign() {
    String geositePaste =
        """
        Please import the Geographic Site specification from APIHub into the CIP catalog.
        I have an approved IDS (shortened below).
        Import intent: IMPORT_SPECIFICATION / catalog import from APIHub — not gather-only
        requirements for a greenfield design.
        """;

    assertEquals(
        ScenarioType.IMPORT_SPECIFICATION,
        RouterHeuristics.tryFastResolve(geositePaste).orElseThrow());
  }

  @Test
  void exactImportSpecificationCommandStillRoutes() {
    assertEquals(
        ScenarioType.IMPORT_SPECIFICATION,
        RouterHeuristics.tryFastResolve("Import specification").orElseThrow());
  }
}
