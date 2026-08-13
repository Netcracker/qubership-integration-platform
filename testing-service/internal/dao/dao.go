// Package dao holds the bun models, the repositories over them, and the query
// helpers the repositories share.
package dao

import (
	"log/slog"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
)

type Dao struct {
	db     config.DB
	logger *slog.Logger

	TestsRunsRepository          TestsRunsRepository
	TestCasesRepository          TestCasesRepository
	TestCaseRunsRepository       TestCaseRunsRepository
	TestCaseRunErrorsRepository  TestCaseRunErrorsRepository
	TriggerReferencesRepository  TriggerReferencesRepository
	RequestSettingsRepository    RequestSettingsRepository
	MessagesRepository           MessagesRepository
	HeadersRepository            HeadersRepository
	QueryParametersRepository    QueryParametersRepository
	PathParametersRepository     PathParametersRepository
	MatchersRepository           MatchersRepository
	MatcherParametersRepository  MatcherParametersRepository
	EndpointMocksRepository      EndpointMocksRepository
	EndpointReferencesRepository EndpointReferencesRepository
	ResponseSettingsRepository   ResponseSettingsRepository
}

// NewDao wires the repositories over the database the host supplied. The
// pagination limit comes from cfg, so nothing below reads configuration itself.
func NewDao(cfg config.Config, deps config.Deps) *Dao {
	cfg = cfg.WithDefaults()
	logger := deps.Logger
	if logger == nil {
		logger = slog.Default()
	}
	limit := cfg.PaginationLimit
	return &Dao{
		db:                           deps.DB,
		logger:                       logger,
		TestsRunsRepository:          NewTestsRunsRepository(logger, limit),
		TestCaseRunsRepository:       NewTestCaseRunsRepository(logger, limit),
		TestCasesRepository:          NewTestCasesRepository(logger, limit),
		TestCaseRunErrorsRepository:  NewTestCaseRunErrorsRepository(),
		TriggerReferencesRepository:  NewTriggerReferencesRepository(),
		RequestSettingsRepository:    NewRequestSettingsRepository(),
		MessagesRepository:           NewMessagesRepository(),
		HeadersRepository:            NewHeadersRepository(),
		QueryParametersRepository:    NewQueryParametersRepository(),
		PathParametersRepository:     NewPathParametersRepository(),
		MatchersRepository:           NewMatchersRepository(),
		MatcherParametersRepository:  NewMatcherParametersRepository(),
		EndpointMocksRepository:      NewEndpointMocksRepository(logger, limit),
		EndpointReferencesRepository: NewEndpointReferencesRepository(),
		ResponseSettingsRepository:   NewResponseSettingsRepository(),
	}
}
