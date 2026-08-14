// Package controllers holds the HTTP layer: the handlers over the services, the
// helpers they answer with, and the route table Mount registers.
package controllers

import (
	"github.com/gofiber/fiber/v2"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services"
)

// Controllers is the HTTP layer over a wired set of services.
type Controllers struct {
	currentUser       config.CurrentUserFunc
	testCases         *testCasesController
	testsRuns         *testsRunsController
	testCaseRuns      *testCaseRunsController
	testCaseRunErrors *testCaseRunErrorsController
	endpointMocks     *endpointMocksController
	serviceMode       *serviceModeController
}

// New wires the handlers over the given services.
func New(cfg config.Config, deps config.Deps, svcs *services.Services) *Controllers {
	logger := deps.Logger
	return &Controllers{
		currentUser:       deps.CurrentUser,
		testCases:         newTestCasesController(logger, svcs.TestCasesService),
		testsRuns:         newTestsRunsController(logger, svcs.TestsRunsService),
		testCaseRuns:      newTestCaseRunsController(logger, svcs.TestCaseRunsService),
		testCaseRunErrors: newTestCaseRunErrorsController(logger, svcs.TestCaseRunErrorsService),
		endpointMocks:     newEndpointMocksController(logger, svcs.EndpointMocksService),
		serviceMode:       newServiceModeController(cfg.ProductionMode()),
	}
}

// Mount
// @title Testing Service API
// @description API of the testing service of the Qubership Integration Platform.
// @tag.name V1
// @tag.description operations for v1 apis
// @tag.name Tests Runs
// @tag.description operations for tests runs
// @tag.name Test Case Runs
// @tag.description operations for test case runs
// @tag.name Test Cases
// @tag.description operations for test cases
// @tag.name Endpoint Mocks
// @tag.description operations for endpoint mocks
// @Produce json
//
// Mount registers the routes on router, along with the middleware that resolves
// the caller. The paths are relative: the host decides where the API lives, and
// the standalone binary puts it under /api/v1.
//
// Registration order matters. Fiber matches routes in the order they were added,
// so a literal segment such as /test-cases/create has to precede the
// /test-cases/:id pattern that would otherwise swallow it.
func (c *Controllers) Mount(router fiber.Router) {
	router.Use(CurrentUserMiddleware(c.currentUser))

	router.Post("/test-cases", c.testCases.FindAll)
	router.Post("/test-cases/create", c.testCases.Create)
	router.Post("/test-cases/import", c.testCases.Import)
	router.Post("/test-cases/export", c.testCases.Export)
	router.Get("/test-cases/:id", c.testCases.FindById)
	router.Post("/test-cases/:id", c.testCases.Update)
	router.Delete("/test-cases", c.testCases.BulkDelete)
	router.Delete("/test-cases/:id", c.testCases.Delete)

	router.Post("/tests-runs", c.testsRuns.FindAll)
	router.Delete("/tests-runs", c.testsRuns.BulkDelete)
	router.Post("/tests-runs/create", c.testsRuns.StartNew)
	router.Post("/tests-runs/cancel", c.testsRuns.BulkCancel)
	router.Post("/tests-runs/export", c.testsRuns.BulkExport)
	router.Get("/tests-runs/:id", c.testsRuns.FindById)
	router.Delete("/tests-runs/:id", c.testsRuns.Delete)
	router.Post("/tests-runs/:id/cancel", c.testsRuns.Cancel)
	router.Post("/tests-runs/:id/export", c.testsRuns.Export)

	router.Post("/test-case-runs", c.testCaseRuns.FindAll)
	router.Post("/test-case-runs/cancel", c.testCaseRuns.BulkCancel)
	router.Post("/test-case-runs/export", c.testCaseRuns.BulkExport)
	router.Post("/test-case-runs/errors/export", c.testCaseRunErrors.BulkExport)
	router.Get("/test-case-runs/:id", c.testCaseRuns.FindById)
	router.Post("/test-case-runs/:id/cancel", c.testCaseRuns.Cancel)
	router.Post("/test-case-runs/:id/export", c.testCaseRuns.Export)
	router.Get("/test-case-runs/:id/errors", c.testCaseRunErrors.FindByTestCaseRunId)

	router.Post("/endpoint-mocks", c.endpointMocks.FindAll)
	router.All("/endpoint-mocks/call", c.endpointMocks.Call)
	router.Post("/endpoint-mocks/create", c.endpointMocks.Create)
	router.Post("/endpoint-mocks/import", c.endpointMocks.Import)
	router.Post("/endpoint-mocks/export", c.endpointMocks.Export)
	router.Get("/endpoint-mocks/:id", c.endpointMocks.FindById)
	router.Post("/endpoint-mocks/:id", c.endpointMocks.Update)
	router.Delete("/endpoint-mocks", c.endpointMocks.BulkDelete)
	router.Delete("/endpoint-mocks/:id", c.endpointMocks.Delete)

	router.Get("/mode", c.serviceMode.GetMode)
}
