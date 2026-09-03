package controllers

import (
	"encoding/json"
	"errors"
	"log/slog"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services"
)

type testCasesController struct {
	responder
	testCasesService services.TestCasesService
}

func newTestCasesController(logger *slog.Logger, testCasesService services.TestCasesService) *testCasesController {
	return &testCasesController{responder: responder{logger: logger}, testCasesService: testCasesService}
}

// FindAll
// @Summary Get test cases
// @ID findTestsCasesV1
// @Tags V1, Test Cases
// @Accept   json
// @Produce  json
// @Param    offset           query    int                       false    "Offset"
// @Param    limit            query    int                       false    "Limit"
// @Param    sort_by          query    string                    false    "Sort field"    enums(id, name, description, enabled, chain_id, element_id, created_by, created_at, updated_by, updated_at, validation_rule_count, enabled_rule_count)
// @Param    sort_order       query    string                    false    "Sort order"    enums(ASC, DESC) default(ASC)
// @Param    return_ids       query    bool                      false    "Return IDs list"
// @Param    specification    body     SelectionSpecification    true     "Test cases specification"
// @Success    200    array       TestCaseView
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-cases [post]
func (c *testCasesController) FindAll(ctx *fiber.Ctx) error {
	return findAll(ctx, c.responder, "test cases", c.testCasesService.FindAll,
		func(testCase dao.TestCaseView) uuid.UUID { return testCase.ID })
}

// FindById
// @Summary Get test case
// @ID findTestCaseByIdV1
// @Tags V1, Test Cases
// @Produce json
// @Param    id    path    string    true    "Test case UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Success    200    {object}    TestCaseView
// @Failure    400    {object}    ErrorMessage
// @Failure    404    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-cases/{id} [get]
func (c *testCasesController) FindById(ctx *fiber.Ctx) error {
	return findByID(ctx, c.responder, "test case", "Test case", c.testCasesService.FindById)
}

// Create
// @Summary Create test case
// @ID createTestCaseV1
// @Tags V1, Test Cases
// @Accept json
// @Param  testCase  body  TestCase  true  "Test case"
// @Success    201    {object}    TestCase
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-cases/create [post]
func (c *testCasesController) Create(ctx *fiber.Ctx) error {
	var testCase dao.TestCase
	if err := json.Unmarshal(ctx.Body(), &testCase); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	createdTestCase, err := c.testCasesService.Create(userContext, &testCase)
	if err != nil {
		if errors.Is(err, services.ErrInvalidRequest) {
			return c.fail(ctx, fiber.StatusBadRequest, "%s", err.Error())
		}
		return c.internalError(ctx, "Unable to create test case", err)
	}
	return ctx.Status(fiber.StatusCreated).JSON(createdTestCase)
}

// Update
// @Summary Update test case
// @ID updateTestCaseV1
// @Tags V1, Test Cases
// @Accept json
// @Param    id        path    string    true    "Test case UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Param    testCase  body    TestCase  true    "Test case"
// @Success    200    {object}    TestCase
// @Failure    400    {object}    ErrorMessage
// @Failure    404    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-cases/{id} [post]
func (c *testCasesController) Update(ctx *fiber.Ctx) error {
	idString := ctx.Params("id")
	testCaseId, err := uuid.Parse(idString)
	if err != nil {
		return c.malformedUUID(ctx, idString, err)
	}
	var testCase dao.TestCase
	if err := json.Unmarshal(ctx.Body(), &testCase); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	testCase.ID = testCaseId
	userContext := ctx.UserContext()
	updatedTestCase, err := c.testCasesService.Update(userContext, &testCase)
	if err != nil {
		switch {
		case errors.Is(err, services.ErrInvalidRequest):
			return c.fail(ctx, fiber.StatusBadRequest, "%s", err.Error())
		case errors.Is(err, services.ErrNotFound):
			// The id came off the path and names nothing, which is what the read
			// endpoint answers 404 to. The wording matches it.
			return c.fail(ctx, fiber.StatusNotFound, "Test case %v not found.", testCaseId)
		}
		return c.internalError(ctx, "Unable to update test case", err)
	}
	return ctx.Status(fiber.StatusOK).JSON(updatedTestCase)
}

// Delete
// @Summary Delete test case
// @ID deleteTestCaseV1
// @Tags V1, Test Cases
// @Produce json
// @Param    id    path    string    true    "Test case UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Success    204
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-cases/{id} [delete]
func (c *testCasesController) Delete(ctx *fiber.Ctx) error {
	return c.actOnID(ctx, "Failed to delete test case", c.testCasesService.Delete)
}

// BulkDelete
// @Summary Delete test cases
// @ID deleteTestsCasesV1
// @Tags V1, Test Cases
// @Accept  json
// @Produce json
// @Param   ids body []string true "Test cases IDs to delete"
// @Success    204
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-cases [delete]
func (c *testCasesController) BulkDelete(ctx *fiber.Ctx) error {
	return c.actOnIDs(ctx, "Failed to bulk delete test cases", c.testCasesService.BulkDelete)
}

// Import
// @Summary Import test cases
// @ID importTestCasesV1
// @Tags V1, Test Cases
// @Accept mpfd
// @Produce json
// @Success 200 array ImportResult
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-cases/import [post]
func (c *testCasesController) Import(ctx *fiber.Ctx) error {
	form, err := ctx.MultipartForm()
	if err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	importResult, err := c.testCasesService.Import(userContext, form.File["file"])
	if err != nil {
		return c.internalError(ctx, "Failed to import test cases", err)
	}
	return ctx.Status(fiber.StatusOK).JSON(importResult)
}

// Export
// @Summary Export test cases
// @ID exportTestCasesV1
// @Tags V1, Test Cases
// @Accept json
// @Produce octet-stream
// @Param   ids body []string true "Test cases IDs to export"
// @Success 200
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/test-cases/export [post]
func (c *testCasesController) Export(ctx *fiber.Ctx) error {
	var testCaseIds []uuid.UUID
	if err := json.Unmarshal(ctx.Body(), &testCaseIds); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	data, err := c.testCasesService.Export(userContext, &testCaseIds)
	if err != nil {
		return c.internalError(ctx, "Failed to export test cases", err)
	}
	return respondWithZip(ctx, fiber.StatusOK, *data)
}
