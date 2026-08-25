package controllers

import (
	"encoding/json"
	"log/slog"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services"
)

type testCaseRunErrorsController struct {
	responder
	testCaseRunErrorsService services.TestCaseRunErrorsService
}

func newTestCaseRunErrorsController(
	logger *slog.Logger,
	testCaseRunErrorsService services.TestCaseRunErrorsService,
) *testCaseRunErrorsController {
	return &testCaseRunErrorsController{
		responder:                responder{logger: logger},
		testCaseRunErrorsService: testCaseRunErrorsService,
	}
}

// FindByTestCaseRunId
// @Summary Get test case run errors
// @ID findTestCaseRunErrorsV1
// @Tags V1, Test Case Runs
// @Produce json
// @Param    id              path     string    true    "Test case run UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Param    withMatchers    query    boolean   false   "Include corresponding matchers"
// @Success    200    array       ValidationError
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-case-runs/{id}/errors [get]
func (c *testCaseRunErrorsController) FindByTestCaseRunId(ctx *fiber.Ctx) error {
	idString := ctx.Params("id")
	testCaseRunId, err := uuid.Parse(idString)
	if err != nil {
		return c.malformedUUID(ctx, idString, err)
	}
	withMatchers := ctx.QueryBool("withMatchers", false)
	userContext := ctx.UserContext()
	validationErrors, err := c.testCaseRunErrorsService.FindByTestCaseRunId(userContext, testCaseRunId, withMatchers)
	if err != nil {
		return c.internalError(ctx, "Unable to get validation errors by test case run ID", err)
	}
	return ctx.Status(fiber.StatusOK).JSON(validationErrors)
}

// BulkExport
// @Summary Export validation errors
// @ID exportValidationErrorsV1
// @Tags V1, Test Case Runs
// @Accept  json
// @Produce text/csv
// @Param ids body []string true "Validation errors IDs to export"
// @Success 200
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-case-runs/errors/export [post]
func (c *testCaseRunErrorsController) BulkExport(ctx *fiber.Ctx) error {
	var validationErrorIds []uuid.UUID
	if err := json.Unmarshal(ctx.Body(), &validationErrorIds); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	result, err := c.testCaseRunErrorsService.BulkExport(userContext, &validationErrorIds)
	if err != nil {
		return c.internalError(ctx, "Failed to export validation errors", err)
	}
	return respondWithCsv(ctx, fiber.StatusOK, result)
}
