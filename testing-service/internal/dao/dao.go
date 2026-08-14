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
	TriggerReferences  TriggerReferencesRepository
	RequestSettings    RequestSettingsRepository
	Messages           MessagesRepository
	Headers            HeadersRepository
	QueryParameters    QueryParametersRepository
	PathParameters     PathParametersRepository
	Matchers           MatchersRepository
	MatcherParameters  MatcherParametersRepository
	EndpointMocks      EndpointMocksRepository
	EndpointReferences EndpointReferencesRepository
	ResponseSettings   ResponseSettingsRepository
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
			TriggerReferences:  NewTriggerReferencesRepository(),
			RequestSettings:    NewRequestSettingsRepository(),
			Messages:           NewMessagesRepository(),
			Headers:            NewHeadersRepository(),
			QueryParameters:    NewQueryParametersRepository(),
			PathParameters:     NewPathParametersRepository(),
			Matchers:           NewMatchersRepository(),
			MatcherParameters:  NewMatcherParametersRepository(),
			EndpointMocks:      NewEndpointMocksRepository(logger, limit),
			EndpointReferences: NewEndpointReferencesRepository(),
			ResponseSettings:   NewResponseSettingsRepository(),
		},
	}
}
