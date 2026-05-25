package org.qubership.integration.platform.ai.integration.catalog.tool;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferElementsPlacementHintsTest {

  @Test
  void enrichConditionParentScriptChildListsIfElseShellIds() {
    CatalogElementResponseDto parent = new CatalogElementResponseDto();
    parent.id = "cond-1";
    parent.type = "condition";
    CatalogElementResponseDto ifShell = new CatalogElementResponseDto();
    ifShell.id = "if-1";
    ifShell.type = "if";
    CatalogElementResponseDto elseShell = new CatalogElementResponseDto();
    elseShell.id = "else-1";
    elseShell.type = "else";
    parent.children = List.of(ifShell, elseShell);

    CatalogElementResponseDto moved = new CatalogElementResponseDto();
    moved.id = "script-1";
    moved.type = "script";

    String out =
        TransferElementsPlacementHints.enrichPlacementError(
            parent, moved, "parent type `condition` only allows children [if, else]");

    assertTrue(out.contains("How to fix"));
    assertTrue(out.contains("if=if-1"));
    assertTrue(out.contains("else=else-1"));
    assertTrue(out.contains("Do not pass the condition element id"));
  }
}
