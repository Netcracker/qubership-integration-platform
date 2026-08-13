package controllers

import (
	"encoding/json"
	"errors"
	"log/slog"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services"
)

type testsRunsController struct {
	responder
	testsRunsService services.TestsRunsService
}

func newTestsRunsController(logger *slog.Logger, testsRunsService services.TestsRunsService) *testsRunsController {
	return &testsRunsController{responder: responder{logger: logger}, testsRunsService: testsRunsService}
}

// FindAll
// @Summary Get tests runs
// @ID findTestsRunsV1
// @Tags V1, Tests Runs
// @Accept   json
// @Produce  json
// @Param    offset           query    int         false    "Offset"
// @Param    limit            query    int         false    "Limit"
// @Param    sort_by          query    string      false    "Sort field"    enums(id, start, finish, status, errors, test_cases, created_by, created_at)
// @Param    sort_order       query    string      false    "Sort order"    enums(ASC, DESC) default(ASC)
// @Param    return_ids       query    bool                      false    "Return IDs list"
// @Param    specification    body     SelectionSpecification    true     "Tests runs specification"
// @Success    200    array       TestsRunView
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/tests-runs [post]
func (c *testsRunsController) FindAll(ctx *fiber.Ctx) error {
	pagination, err := paginationOptions(ctx)
	if err != nil {
		return c.malformedPaginationParameters(ctx, err)
	}
	sorting, err := sortOptions(ctx)
	if err != nil {
		return c.malformedSortingParameters(ctx, err)
	}
	var specification model.SelectionSpecification
	if err := json.Unmarshal(ctx.Body(), &specification); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	returnIds := ctx.QueryBool("return_ids", false)
	if returnIds {
		pagination = nil
	}
	userContext := ctx.UserContext()
	testsRuns, err := c.testsRunsService.FindAll(userContext, &specification, *sorting, pagination)
	if err != nil {
		return c.fail(ctx, fiber.StatusInternalServerError, "Unable to get tests runs: %v", err.Error())
	}
	if returnIds {
		ids := make([]uuid.UUID, 0, len(*testsRuns))
		for _, testsRun := range *testsRuns {
			ids = append(ids, testsRun.ID)
		}
		return respondWithJSON(ctx, fiber.StatusOK, ids)
	}
	return respondWithJSON(ctx, fiber.StatusOK, testsRuns)
}

// FindById
// @Summary Get tests run
// @ID findTestsRunByIdV1
// @Tags V1, Tests Runs
// @Produce json
// @Param    id    path    string    true    "Tests run UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Success    200    {object}    TestsRunView
// @Failure    400    {object}    ErrorMessage
// @Failure    404    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/tests-runs/{id} [get]
func (c *testsRunsController) FindById(ctx *fiber.Ctx) error {
	idString := ctx.Params("id")
	testsRunId, err := uuid.Parse(idString)
	if err != nil {
		return c.malformedUUID(ctx, idString, err)
	}
	userContext := ctx.UserContext()
	testsRun, err := c.testsRunsService.FindById(userContext, testsRunId)
	if err != nil {
		return c.fail(ctx, fiber.StatusInternalServerError, "Unable to get tests run by ID: %v", err.Error())
	}
	if testsRun == nil {
		return c.fail(ctx, fiber.StatusNotFound, "Tests run %v not found.", testsRunId)
	}
	return respondWithJSON(ctx, fiber.StatusOK, testsRun)
}

// StartNew
// @Summary Start new tests run
// @ID startNewTestsRunV1
// @Tags V1, Tests Runs
// @Accept  json
// @Produce json
// @Param      ids    body     []string    true     "Entities IDs to run corresponding test cases"
// @Param      from   query    string      false    "Entity type"    enums(test_cases, tests_runs, test_case_runs) default(test_cases)
// @Success    201    {string}    uuid.UUID         "Created tests run ID"
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/tests-runs/create [post]
func (c *testsRunsController) StartNew(ctx *fiber.Ctx) error {
	var ids []uuid.UUID
	if err := json.Unmarshal(ctx.Body(), &ids); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	entityType := ctx.Query("from", services.EntityTypeTestCases)
	testRunId, err := c.testsRunsService.StartNewFromEntitiesWithType(userContext, &ids, entityType)
	switch {
	case err == nil:
		return respondWithJSON(ctx, fiber.StatusCreated, testRunId)
	case errors.Is(err, services.ErrEmptyTestCaseList):
		return c.fail(ctx, fiber.StatusBadRequest, "%s", err.Error())
	default:
		return c.fail(ctx, fiber.StatusInternalServerError, "Failed to start new tests run: %v", err.Error())
	}
}

// Delete
// @Summary Delete tests run
// @ID deleteTestsRunV1
// @Tags V1, Tests Runs
// @Produce json
// @Param    id    path    string    true    "Tests run UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Success    204
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/tests-runs/{id} [delete]
func (c *testsRunsController) Delete(ctx *fiber.Ctx) error {
	idString := ctx.Params("id")
	testsRunId, err := uuid.Parse(idString)
	if err != nil {
		return c.malformedUUID(ctx, idString, err)
	}
	userContext := ctx.UserContext()
	if err := c.testsRunsService.Delete(userContext, testsRunId); err != nil {
		return c.fail(ctx, fiber.StatusInternalServerError, "Failed to delete tests run: %v", err.Error())
	}
	return ctx.SendStatus(fiber.StatusNoContent)
}

// BulkDelete
// @Summary Delete tests runs
// @ID deleteTestsRunsV1
// @Tags V1, Tests Runs
// @Accept  json
// @Produce json
// @Param   ids body []string true "Tests runs IDs to delete"
// @Success    204
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/tests-runs [delete]
func (c *testsRunsController) BulkDelete(ctx *fiber.Ctx) error {
	var testsRunsIds []uuid.UUID
	if err := json.Unmarshal(ctx.Body(), &testsRunsIds); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	if err := c.testsRunsService.BulkDelete(userContext, &testsRunsIds); err != nil {
		return c.fail(ctx, fiber.StatusInternalServerError, "Failed to bulk delete tests runs: %v", err.Error())
	}
	return ctx.SendStatus(fiber.StatusNoContent)
}

// Cancel
// @Summary Cancel tests run
// @ID cancelTestsRunByIdV1
// @Tags V1, Tests Runs
// @Param    id    path    string    true    "Tests run UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Success    204
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/tests-runs/{id}/cancel [post]
func (c *testsRunsController) Cancel(ctx *fiber.Ctx) error {
	idString := ctx.Params("id")
	testsRunId, err := uuid.Parse(idString)
	if err != nil {
		return c.malformedUUID(ctx, idString, err)
	}
	userContext := ctx.UserContext()
	if err := c.testsRunsService.Cancel(userContext, testsRunId); err != nil {
		return c.fail(ctx, fiber.StatusInternalServerError, "Failed to cancel tests run: %v", err.Error())
	}
	return ctx.SendStatus(fiber.StatusNoContent)
}

// BulkCancel
// @Summary Cancel tests runs
// @ID cancelTestsRunsV1
// @Tags V1, Tests Runs
// @Accept  json
// @Param   ids body []string true "Tests runs IDs to cancel"
// @Success    204
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/tests-runs/cancel [post]
func (c *testsRunsController) BulkCancel(ctx *fiber.Ctx) error {
	var testsRunsIds []uuid.UUID
	if err := json.Unmarshal(ctx.Body(), &testsRunsIds); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	if err := c.testsRunsService.BulkCancel(userContext, &testsRunsIds); err != nil {
		return c.fail(ctx, fiber.StatusInternalServerError, "Failed to bulk cancel tests runs: %v", err.Error())
	}
	return ctx.SendStatus(fiber.StatusNoContent)
}

// Export
// @Summary Export tests run
// @ID exportTestsRunV1
// @Tags V1, Tests Runs
// @Accept  json
// @Produce text/csv
// @Param    id    path    string    true    "Tests run UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Success 200
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/tests-runs/{id}/export [post]
func (c *testsRunsController) Export(ctx *fiber.Ctx) error {
	idString := ctx.Params("id")
	testsRunId, err := uuid.Parse(idString)
	if err != nil {
		return c.malformedUUID(ctx, idString, err)
	}
	userContext := ctx.UserContext()
	testsRunsIds := []uuid.UUID{testsRunId}
	result, err := c.testsRunsService.Export(userContext, &testsRunsIds)
	if err != nil {
		return c.fail(ctx, fiber.StatusInternalServerError, "Failed to export tests run result: %v", err.Error())
	}
	return respondWithCsv(ctx, fiber.StatusOK, result)
}

// BulkExport
// @Summary Export tests runs
// @ID exportTestsRunsV1
// @Tags V1, Tests Runs
// @Accept  json
// @Produce text/csv
// @Param ids body []string true "Tests runs IDs to export"
// @Success 200
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/tests-runs/export [post]
func (c *testsRunsController) BulkExport(ctx *fiber.Ctx) error {
	var testsRunsIds []uuid.UUID
	if err := json.Unmarshal(ctx.Body(), &testsRunsIds); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	result, err := c.testsRunsService.Export(userContext, &testsRunsIds)
	if err != nil {
		return c.fail(ctx, fiber.StatusInternalServerError, "Failed to export tests runs result: %v", err.Error())
	}
	return respondWithCsv(ctx, fiber.StatusOK, result)
}
