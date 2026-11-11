---
name: testing-service-internals
description: Explains how the testing service picks the engine a test case activates, how a DAO repository is added, and how create and update validation treat values already stored. Use when changing engine-address resolution or trigger activation, adding a repository under testing-service/internal/dao, or changing the save-time checks of test cases and endpoint mocks.
---

# Testing service internals

Each section below covers one task. The always-on rules of the module are in the `testing-service` instruction.

## Engine resolution

Read [engine-address.md](engine-address.md) before changing `internal/services/engine_address.go`, `qip.Deployment`,
`qip.CatalogClient.FindChainDeployments`, or the address `triggers.Factory.GetTrigger` receives.

## Adding a repository

A child table written with nothing but insert, update and delete gets no file of its own. `dao.NewCrudRepository[T]()`
in `internal/dao/crud.go` implements all four statements, and its field in `dao.Repositories` names the narrowest
composed interface its callers use: `Inserter`, `BulkInserter`, `InsertDeleter`, or `InsertUpdateDeleter`.

A table with queries of its own keeps a file, as `test_cases_repository.go` and `test_case_runs_repository.go` do.
`matchers_repository.go` is the mixed case: `Insert` delegates to `insertRow`, and only `DeleteByOwnerId` and
`DeleteByOwnerIds` are written out. A bulk delete returns early on an empty id list, as every `BulkDelete` and
`DeleteByOwnerIds` does, because it does not go through `dao.AddSpecification`.

The repository follows the DAO naming of the module (`FindById`, `ids *[]uuid.UUID`) and takes the pagination limit as a
plain `int` rather than reading configuration.

## Create and update validation

The save-time rules live in `endpointMockViolations` and `testCaseViolations`. Both run the matcher checks
(`matcherViolations` in `internal/services/matchers_service.go`). A mock adds the 100–599 range of its response status
and the field-name and field-value checks of `internal/httpfield` on its response headers; a test case adds the request
method, which the `http_method` enum column keeps out of every stored row. The rules collect `violation` values instead
of returning on the first one, and each violation carries a key naming the offending value alone.

`doCreate` refuses any violation with a 400. `doUpdate` reads the stored row first and passes the incoming and stored
violations to `tolerateStoredViolations`, which refuses every violation whose key the stored row does not carry and logs
the rest at WARN. A row saved before a rule existed still reads back (an unbuildable matcher is skipped, an unwritable
header is left out of the response), and an update validates the whole entity, so without this a single legacy value
would block every other edit of the row.

The key decides how far that leniency reaches:

- A key names the value, never the element carrying it. Keyed by the matcher or the header, an update could swap the
  value under a legacy element for any other bad value. Keyed by the value, the caller may rename and re-describe the
  element while the refused value stays as it was stored.
- `parametersKey` sorts the parameters, so reordering the parameters of a legacy matcher yields the same key.
- Replacing one bad value with a different bad value is a 400, because the new key was never stored. A row with several
  legacy values tolerates each of them and nothing else.

Because `doUpdate` reads the stored row before it validates, a payload that is invalid for an entity that does not exist
gets a 404, not a 400. Import calls `doCreate` for an archive entry that creates an entity and `doUpdate` for one that
updates an existing entity, so the first is strict and the second is lenient.

A new rule goes into the violations function of its entity, with a key built from the offending value. Cover it the
way `internal/services/endpoint_mocks_crud_test.go` covers the existing rules: an update that keeps a stored bad value
succeeds, and one that introduces a new or different bad value is refused.

The JSON decoder drops a field it does not know, so none of these rules sees a misspelled field: a mock created with
`elementReference` instead of `endpointReference` answers 201 and is stored with no reference, where no call reaches it.
