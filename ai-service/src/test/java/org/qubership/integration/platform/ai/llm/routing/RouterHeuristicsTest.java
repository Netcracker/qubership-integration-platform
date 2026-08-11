package org.qubership.integration.platform.ai.llm.routing;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterHeuristicsTest {

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
  void idsPasteMentioningCatalogImportStillRoutesToImportSpecification() {
    String idsPaste =
        """
        I need to create a new chain. I have a design for it

        # Integration Design Specification (IDS)

        | Comments | Catalog import smoke test: import Geographic Site from APIHub into CIP catalog |

        #### Catalog companions (import from APIHub)

        | `*.specification.cip.yaml` | parentId → group id |
        """;

    assertEquals(
        ScenarioType.IMPORT_SPECIFICATION,
        RouterHeuristics.tryFastResolve(idsPaste).orElseThrow());
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
