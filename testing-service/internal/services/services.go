// Package services holds the logic over the repositories: the operations behind
// the HTTP API, zip import and export, and the executor that runs queued test
// cases.
package services

import (
	"log/slog"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/qip"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/triggers"
)

// Repositories groups the repository interfaces the services run their queries
// through. A service takes it together with a dao.Runner, because faking the
// runner alone still leaves the repositories talking to a real database.
type Repositories struct {
	TestsRuns          dao.TestsRunsRepository
	TestCases          dao.TestCasesRepository
	TestCaseRuns       dao.TestCaseRunsRepository
	TestCaseRunErrors  dao.TestCaseRunErrorsRepository
	TriggerReferences  dao.TriggerReferencesRepository
	RequestSettings    dao.RequestSettingsRepository
	Messages           dao.MessagesRepository
	Headers            dao.HeadersRepository
	QueryParameters    dao.QueryParametersRepository
	PathParameters     dao.PathParametersRepository
	Matchers           dao.MatchersRepository
	MatcherParameters  dao.MatcherParametersRepository
	EndpointMocks      dao.EndpointMocksRepository
	EndpointReferences dao.EndpointReferencesRepository
	ResponseSettings   dao.ResponseSettingsRepository
}

// RepositoriesOf reads the repositories off a wired Dao.
func RepositoriesOf(d *dao.Dao) Repositories {
	return Repositories{
		TestsRuns:          d.TestsRunsRepository,
		TestCases:          d.TestCasesRepository,
		TestCaseRuns:       d.TestCaseRunsRepository,
		TestCaseRunErrors:  d.TestCaseRunErrorsRepository,
		TriggerReferences:  d.TriggerReferencesRepository,
		RequestSettings:    d.RequestSettingsRepository,
		Messages:           d.MessagesRepository,
		Headers:            d.HeadersRepository,
		QueryParameters:    d.QueryParametersRepository,
		PathParameters:     d.PathParametersRepository,
		Matchers:           d.MatchersRepository,
		MatcherParameters:  d.MatcherParametersRepository,
		EndpointMocks:      d.EndpointMocksRepository,
		EndpointReferences: d.EndpointReferencesRepository,
		ResponseSettings:   d.ResponseSettingsRepository,
	}
}

// Services is the set the HTTP layer and the executor are built on.
type Services struct {
	TestsRunsService         TestsRunsService
	TestCaseRunsService      TestCaseRunsService
	TestCaseRunErrorsService TestCaseRunErrorsService
	TestCasesService         TestCasesService
	EndpointMocksService     EndpointMocksService
	TriggerResolverService   TriggerResolverService
	TestExecutionService     TestExecutionService
}

// NewServices wires the services over the given database access. The platform
// clients are built from cfg, so nothing below this function reads configuration.
func NewServices(cfg config.Config, deps config.Deps, d *dao.Dao) *Services {
	cfg = cfg.WithDefaults()
	logger := deps.Logger
	if logger == nil {
		logger = slog.Default()
	}
	repositories := RepositoriesOf(d)

	catalogClient := qip.NewCatalogClient(cfg.CatalogAddress, deps.HTTPClient)
	engineClient := qip.NewEngineClient(cfg.EngineAddress)
	triggerFactory := triggers.NewFactory(engineClient, deps.HTTPClient)

	matchersService := NewMatchersService(repositories)
	testCasesService := NewTestCasesService(d, repositories, matchersService)
	testCaseRunsService := NewTestCaseRunsService(cfg, d, repositories)
	testCaseRunErrorsService := NewTestCaseRunErrorsService(d, repositories)
	triggerResolverService := NewTriggerResolverService(catalogClient, triggerFactory)

	return &Services{
		TestsRunsService:         NewTestsRunsService(d, repositories, testCaseRunsService),
		TestCaseRunsService:      testCaseRunsService,
		TestCaseRunErrorsService: testCaseRunErrorsService,
		TestCasesService:         testCasesService,
		EndpointMocksService:     NewEndpointMocksService(d, repositories, matchersService),
		TriggerResolverService:   triggerResolverService,
		TestExecutionService: NewTestExecutionService(
			cfg,
			logger,
			testCasesService,
			testCaseRunsService,
			testCaseRunErrorsService,
			triggerResolverService,
		),
	}
}
