// Package dao holds the bun models, the repositories over them, and the query
// helpers the repositories share.
package dao

import (
	"log/slog"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
)

// Repositories groups the repository interfaces the callers run their queries
// through. A service takes it together with a Runner, because faking the runner
// alone still leaves the repositories talking to a real database, and a test
// fills in only the fields the path under test reaches for.
type Repositories struct {
	TestsRuns          TestsRunsRepository
	TestCases          TestCasesRepository
	TestCaseRuns       TestCaseRunsRepository
	TestCaseRunErrors  TestCaseRunErrorsRepository
	TriggerReferences  InsertUpdateDeleter[TriggerReference]
	RequestSettings    InsertDeleter[RequestSettings]
	Messages           Inserter[Message]
	Headers            BulkInserter[Header]
	QueryParameters    BulkInserter[QueryParameter]
	PathParameters     BulkInserter[PathParameter]
	Matchers           MatchersRepository
	MatcherParameters  BulkInserter[MatcherParameter]
	EndpointMocks      EndpointMocksRepository
	EndpointReferences InsertUpdateDeleter[EndpointReference]
	ResponseSettings   InsertDeleter[ResponseSettings]
}

type Dao struct {
	db     config.DB
	logger *slog.Logger

	Repositories
}

// NewDao wires the repositories over the database the host supplied. The
// pagination limit comes from cfg, so no repository reads configuration itself.
// It expects cfg and deps to be normalized already.
func NewDao(cfg config.Config, deps config.Deps) *Dao {
	logger := deps.Logger
	limit := cfg.PaginationLimit
	return &Dao{
		db:     deps.DB,
		logger: logger,
		Repositories: Repositories{
			TestsRuns:          NewTestsRunsRepository(logger, limit),
			TestCaseRuns:       NewTestCaseRunsRepository(logger, limit),
			TestCases:          NewTestCasesRepository(logger, limit),
			TestCaseRunErrors:  NewTestCaseRunErrorsRepository(),
			TriggerReferences:  NewCrudRepository[TriggerReference](),
			RequestSettings:    NewCrudRepository[RequestSettings](),
			Messages:           NewCrudRepository[Message](),
			Headers:            NewCrudRepository[Header](),
			QueryParameters:    NewCrudRepository[QueryParameter](),
			PathParameters:     NewCrudRepository[PathParameter](),
			Matchers:           NewMatchersRepository(),
			MatcherParameters:  NewCrudRepository[MatcherParameter](),
			EndpointMocks:      NewEndpointMocksRepository(logger, limit),
			EndpointReferences: NewCrudRepository[EndpointReference](),
			ResponseSettings:   NewCrudRepository[ResponseSettings](),
		},
	}
}
