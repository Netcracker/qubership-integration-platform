// Package services holds the logic over the repositories: the operations behind
// the HTTP API, zip import and export, and the executor that runs queued test
// cases.
package services

import (
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/qip"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/triggers"
)

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

// NewServices wires the services over the given database access. It expects cfg
// and deps to be normalized already, which testingservice.New does once for the
// whole module.
func NewServices(cfg config.Config, deps config.Deps, d *dao.Dao) (*Services, error) {
	repositories := d.Repositories

	catalogClient := qip.NewCatalogClient(cfg.CatalogAddress, deps.HTTPClient)
	triggerFactory := triggers.NewFactory(deps.HTTPClient)

	testCasesService := NewTestCasesService(deps.Logger, d, repositories)
	testCaseRunsService := NewTestCaseRunsService(cfg, d, repositories)
	testCaseRunErrorsService := NewTestCaseRunErrorsService(d, repositories)
	triggerResolverService := NewTriggerResolverService(
		deps.Logger, cfg.EngineAddress, catalogClient, triggerFactory)

	// The executor is wired before the test runs service, which signals it as
	// soon as it has queued a run.
	testExecutionService := NewTestExecutionService(
		cfg,
		deps.Logger,
		testCasesService,
		testCaseRunsService,
		testCaseRunErrorsService,
		triggerResolverService,
	)

	return &Services{
		TestsRunsService: NewTestsRunsService(
			cfg, deps.Logger, d, repositories, testCaseRunsService, testExecutionService),
		TestCaseRunsService:      testCaseRunsService,
		TestCaseRunErrorsService: testCaseRunErrorsService,
		TestCasesService:         testCasesService,
		EndpointMocksService:     NewEndpointMocksService(deps.Logger, d, repositories),
		TriggerResolverService:   triggerResolverService,
		TestExecutionService:     testExecutionService,
	}, nil
}
