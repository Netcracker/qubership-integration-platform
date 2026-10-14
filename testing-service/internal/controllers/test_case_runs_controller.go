package controllers

import (
	"context"
	"encoding/json"
	"log/slog"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services"
)

type testCaseRunsController struct {
	responder
	testCaseRunsService services.TestCaseRunsService
}

func newTestCaseRunsController(
	logger *slog.Logger,
	testCaseRunsService services.TestCaseRunsService,
) *testCaseRunsController {
	return &testCaseRunsController{responder: responder{logger: logger}, testCaseRunsService: testCaseRunsService}
}

// FindAll
// @Summary Get test case runs
// @ID findTestCaseRunsV1
// @Tags V1, Test Case Runs
// @Accept   json
// @Produce  json
// @Param    offset           query    int         false    "Offset"
// @Param    limit            query    int         false    "Limit"
// @Param    sort_by          query    string      false    "Sort field"    enums(id, test_case_name, chain_id, start, finish, status, errors)
// @Param    sort_order       query    string      false    "Sort order"    enums(ASC, DESC) default(ASC)
// @Param    return_ids       query    bool                      false    "Return IDs list"
// @Param    specification    body     SelectionSpecification    true     "Test case runs specification"
// @Success    200    array       TestCaseRunView
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-case-runs [post]
func (c *testCaseRunsController) FindAll(ctx *fiber.Ctx) error {
	// Test case runs carry no relations to leave out, so the withRelations flag
	// the shared handler passes is dropped here.
	query := func(
		ctx context.Context,
		specification *model.SelectionSpecification,
		sorting model.SortOptions,
		pagination *model.PaginationOptions,
		_ bool,
	) (*[]dao.TestCaseRunView, error) {
		return c.testCaseRunsService.FindAll(ctx, specification, sorting, pagination)
	}
	return findAll(ctx, c.responder, "test case runs", query,
		func(testCaseRun dao.TestCaseRunView) uuid.UUID { return testCaseRun.ID })
}

// FindById
// @Summary Get test case run
// @ID findTestCaseRunByIdV1
// @Tags V1, Test Case Runs
// @Produce json
// @Param    id    path    string    true    "Test case run UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Success    200    {object}    TestCaseRunView
// @Failure    400    {object}    ErrorMessage
// @Failure    404    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-case-runs/{id} [get]
func (c *testCaseRunsController) FindById(ctx *fiber.Ctx) error {
	return findByID(ctx, c.responder, "test case run", "Test case run", c.testCaseRunsService.FindById)
}

// Cancel
// @Summary Cancel test case run
// @ID cancelTestCaseRunByIdV1
// @Tags V1, Test Case Runs
// @Param    id    path    string    true    "Test case run UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Success    204
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-case-runs/{id}/cancel [post]
func (c *testCaseRunsController) Cancel(ctx *fiber.Ctx) error {
	return c.actOnID(ctx, "Failed to cancel test case run", c.testCaseRunsService.Cancel)
}

// BulkCancel
// @Summary Cancel test case runs
// @ID cancelTestCaseRunsV1
// @Tags V1, Test Case Runs
// @Accept  json
// @Param   ids body []string true "Test case runs IDs to cancel"
// @Success    204
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-case-runs/cancel [post]
func (c *testCaseRunsController) BulkCancel(ctx *fiber.Ctx) error {
	return c.actOnIDs(ctx, "Failed to bulk cancel test case runs", c.testCaseRunsService.BulkCancel)
}

// Export
// @Summary Export tests case run
// @ID exportTestCaseRunV1
// @Tags V1, Test Case Runs
// @Accept  json
// @Produce text/csv
// @Param    id    path    string    true    "Test case run UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Success 200
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-case-runs/{id}/export [post]
func (c *testCaseRunsController) Export(ctx *fiber.Ctx) error {
	idString := ctx.Params("id")
	testCaseRunId, err := uuid.Parse(idString)
	if err != nil {
		return c.malformedUUID(ctx, idString, err)
	}
	userContext := ctx.UserContext()
	testCaseRunsIds := []uuid.UUID{testCaseRunId}
	result, err := c.testCaseRunsService.Export(userContext, &testCaseRunsIds)
	if err != nil {
		return c.internalError(ctx, "Failed to export test case run result", err)
	}
	return respondWithCsv(ctx, fiber.StatusOK, result)
}

// BulkExport
// @Summary Export test case runs
// @ID exportTestCaseRunsV1
// @Tags V1, Test Case Runs
// @Accept  json
// @Produce text/csv
// @Param ids body []string true "Test case runs IDs to export"
// @Success 200
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-case-runs/export [post]
func (c *testCaseRunsController) BulkExport(ctx *fiber.Ctx) error {
	var testCaseRunsIds []uuid.UUID
	if err := json.Unmarshal(ctx.Body(), &testCaseRunsIds); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	result, err := c.testCaseRunsService.Export(userContext, &testCaseRunsIds)
	if err != nil {
		return c.internalError(ctx, "Failed to export test case runs result", err)
	}
	return respondWithCsv(ctx, fiber.StatusOK, result)
}
