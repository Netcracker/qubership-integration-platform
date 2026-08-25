package org.qubership.integration.platform.ai.integration.apihub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApiHubSearchAuthorizationsTest {

  private final ApiHubSearchAuthorizations authorizations = new ApiHubSearchAuthorizations();

  @Test
  void searchIsUnauthorizedUntilTheServerIssuesOne() {
    assertTrue(authorizations.consume("conv-1").isEmpty());
  }

  @Test
  void authorizationNamesTheSourceFactItWasIssuedFor() {
    authorizations.issue("conv-1", "fact-a", "getInventory", "confirmed catalog miss");

    ApiHubSearchAuthorizations.Authorization authorization =
        authorizations.consume("conv-1").orElseThrow();

    assertEquals("fact-a", authorization.sourceFactId());
    assertEquals("getInventory", authorization.capabilityQuery());
  }

  @Test
  void budgetBoundsTheNumberOfSearches() {
    authorizations.issue("conv-1", "fact-a", "getInventory", "confirmed catalog miss");

    for (int spent = 0; spent < ApiHubSearchAuthorizations.DEFAULT_QUERY_BUDGET; spent++) {
      assertTrue(authorizations.consume("conv-1").isPresent(), "query " + spent);
    }

    assertTrue(authorizations.consume("conv-1").isEmpty());
    assertFalse(authorizations.active("conv-1").isPresent());
  }

  @Test
  void issuingForAnotherFactReplacesTheEarlierScope() {
    authorizations.issue("conv-1", "fact-a", "getInventory", "confirmed catalog miss");
    authorizations.issue("conv-1", "fact-b", "createInvoice", "confirmed catalog miss");

    assertEquals("fact-b", authorizations.consume("conv-1").orElseThrow().sourceFactId());
  }
}
