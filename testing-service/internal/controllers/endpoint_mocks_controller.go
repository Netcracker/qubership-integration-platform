package controllers

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services"
)

// getEndpointReference names the endpoint a testing context points at.
func getEndpointReference(testingContext *model.TestingContext) dao.EndpointReference {
	return dao.EndpointReference{ChainID: testingContext.ChainID, ElementID: testingContext.ElementID}
}

type endpointMocksController struct {
	responder
	endpointMocksService services.EndpointMocksService
}

func newEndpointMocksController(
	logger *slog.Logger,
	endpointMocksService services.EndpointMocksService,
) *endpointMocksController {
	return &endpointMocksController{responder: responder{logger: logger}, endpointMocksService: endpointMocksService}
}

// FindAll
// @Summary Get endpoint mocks
// @ID findEndpointMocksV1
// @Tags V1, Endpoint Mocks
// @Accept   json
// @Produce  json
// @Param    offset           query    int         false    "Offset"
// @Param    limit            query    int         false    "Limit"
// @Param    sort_by          query    string      false    "Sort field"    enums(id, name, description, chain_id, element_id, enabled, status, delay, created_by, created_at, updated_by, updated_at)
// @Param    sort_order       query    string      false    "Sort order"    enums(ASC, DESC) default(ASC)
// @Param    return_ids       query    bool                      false    "Return IDs list"
// @Param    specification    body     SelectionSpecification    true     "Endpoint mocks specification"
// @Success    200    array       EndpointMock
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/endpoint-mocks [post]
func (c *endpointMocksController) FindAll(ctx *fiber.Ctx) error {
	return findAll(ctx, c.responder, "endpoint mocks", c.endpointMocksService.FindAll,
		func(endpointMock dao.EndpointMock) uuid.UUID { return endpointMock.ID })
}

// FindById
// @Summary Get endpoint mock
// @ID findEndpointMockByIdV1
// @Tags V1, Endpoint Mocks
// @Produce json
// @Param    id    path    string    true    "Endpoint mock UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Success    200    {object}    EndpointMock
// @Failure    400    {object}    ErrorMessage
// @Failure    404    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/endpoint-mocks/{id} [get]
func (c *endpointMocksController) FindById(ctx *fiber.Ctx) error {
	return findByID(ctx, c.responder, "endpoint mock", "Endpoint mock", c.endpointMocksService.FindById)
}

// Create
// @Summary Create endpoint mock
// @ID createEndpointMockV1
// @Tags V1, Endpoint Mocks
// @Accept json
// @Param  endpointMock  body  EndpointMock  true  "Endpoint mock"
// @Success    201    {object}    EndpointMock
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/endpoint-mocks/create [post]
func (c *endpointMocksController) Create(ctx *fiber.Ctx) error {
	var endpointMock dao.EndpointMock
	if err := json.Unmarshal(ctx.Body(), &endpointMock); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	createdEndpointMock, err := c.endpointMocksService.Create(userContext, &endpointMock)
	if err != nil {
		if errors.Is(err, services.ErrInvalidRequest) {
			return c.fail(ctx, fiber.StatusBadRequest, "%s", err.Error())
		}
		return c.internalError(ctx, "Unable to create endpoint mock", err)
	}
	return ctx.Status(fiber.StatusCreated).JSON(createdEndpointMock)
}

// Update
// @Summary Update endpoint mock
// @ID updateEndpointMockV1
// @Tags V1, Endpoint Mocks
// @Accept json
// @Param    id            path    string        true    "Endpoint mock UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Param    endpointMock  body    EndpointMock  true    "Endpoint mock"
// @Success    200    {object}    EndpointMock
// @Failure    400    {object}    ErrorMessage
// @Failure    404    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/endpoint-mocks/{id} [post]
func (c *endpointMocksController) Update(ctx *fiber.Ctx) error {
	idString := ctx.Params("id")
	endpointMockId, err := uuid.Parse(idString)
	if err != nil {
		return c.malformedUUID(ctx, idString, err)
	}
	var endpointMock dao.EndpointMock
	if err := json.Unmarshal(ctx.Body(), &endpointMock); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	endpointMock.ID = endpointMockId
	userContext := ctx.UserContext()
	updatedEndpointMock, err := c.endpointMocksService.Update(userContext, &endpointMock)
	if err != nil {
		switch {
		case errors.Is(err, services.ErrInvalidRequest):
			return c.fail(ctx, fiber.StatusBadRequest, "%s", err.Error())
		case errors.Is(err, services.ErrNotFound):
			// The id came off the path and names nothing, which is what the read
			// endpoint answers 404 to. The wording matches it.
			return c.fail(ctx, fiber.StatusNotFound, "Endpoint mock %v not found.", endpointMockId)
		}
		return c.internalError(ctx, "Unable to update endpoint mock", err)
	}
	return ctx.Status(fiber.StatusOK).JSON(updatedEndpointMock)
}

// Delete
// @Summary Delete endpoint mock
// @ID deleteEndpointMockV1
// @Tags V1, Endpoint Mocks
// @Produce json
// @Param    id    path    string    true    "Endpoint mock UUID"    format(uuid) example(00000000-0000-0000-0000-000000000000)
// @Success    204
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/endpoint-mocks/{id} [delete]
func (c *endpointMocksController) Delete(ctx *fiber.Ctx) error {
	return c.actOnID(ctx, "Failed to delete endpoint mock", c.endpointMocksService.Delete)
}

// BulkDelete
// @Summary Delete endpoint mocks
// @ID deleteEndpointMocksV1
// @Tags V1, Endpoint Mocks
// @Accept  json
// @Produce json
// @Param   ids body []string true "Endpoint mocks IDs to delete"
// @Success    204
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/endpoint-mocks [delete]
func (c *endpointMocksController) BulkDelete(ctx *fiber.Ctx) error {
	return c.actOnIDs(ctx, "Failed to bulk delete endpoint mocks", c.endpointMocksService.BulkDelete)
}

// Import
// @Summary Import endpoint mocks
// @ID importEndpointMocksV1
// @Tags V1, Endpoint Mocks
// @Accept mpfd
// @Produce json
// @Success 200 array ImportResult
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/endpoint-mocks/import [post]
func (c *endpointMocksController) Import(ctx *fiber.Ctx) error {
	form, err := ctx.MultipartForm()
	if err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	importResult, err := c.endpointMocksService.Import(userContext, form.File["file"])
	if err != nil {
		return c.internalError(ctx, "Failed to import endpoint mocks", err)
	}
	return ctx.Status(fiber.StatusOK).JSON(importResult)
}

// Export
// @Summary Export endpoint mocks
// @ID exportEndpointMocksV1
// @Tags V1, Endpoint Mocks
// @Accept json
// @Produce octet-stream
// @Param   ids body []string true "Endpoint mocks IDs to export"
// @Success 200
// @Failure    400    {object}    ErrorMessage
// @Failure    500    {object}    ErrorMessage
// @Router /api/v1/endpoint-mocks/export [post]
func (c *endpointMocksController) Export(ctx *fiber.Ctx) error {
	var endpointMocksIds []uuid.UUID
	if err := json.Unmarshal(ctx.Body(), &endpointMocksIds); err != nil {
		return c.malformedRequestBody(ctx, err)
	}
	userContext := ctx.UserContext()
	data, err := c.endpointMocksService.Export(userContext, &endpointMocksIds)
	if err != nil {
		return c.internalError(ctx, "Failed to export endpoint mocks", err)
	}
	return respondWithZip(ctx, fiber.StatusOK, *data)
}

// Call
// @Summary Call endpoint mock
// @ID callEndpointMockV1
// @Tags V1, Endpoint Mocks
// @Router /api/v1/endpoint-mocks/call [get]
// @Router /api/v1/endpoint-mocks/call [post]
// @Router /api/v1/endpoint-mocks/call [put]
// @Router /api/v1/endpoint-mocks/call [patch]
// @Router /api/v1/endpoint-mocks/call [delete]
// @Router /api/v1/endpoint-mocks/call [head]
func (c *endpointMocksController) Call(ctx *fiber.Ctx) error {
	timestamp := time.Now()
	headerValues, ok := ctx.GetReqHeaders()[http.CanonicalHeaderKey(model.TestingContextHeader)]
	if !ok {
		return c.fail(ctx, fiber.StatusBadRequest, "Missing required header: %v", model.TestingContextHeader)
	}
	if len(headerValues) != 1 {
		return c.fail(ctx, fiber.StatusBadRequest, "Wrong %v header value", model.TestingContextHeader)
	}
	testingContext, err := model.DecodeTestingContext(headerValues[0])
	if err != nil {
		return c.fail(ctx, fiber.StatusBadRequest, "Failed to decode testing context: %v", err.Error())
	}

	endpointReference := getEndpointReference(testingContext)
	requestExchange := buildRequestExchange(ctx)
	userContext := services.WithRequestStart(ctx.UserContext(), timestamp)
	exchange, err := c.endpointMocksService.Call(userContext, endpointReference, requestExchange)
	if err != nil {
		return c.internalError(ctx, "Failed to call endpoint mock", err)
	}

	populateResponseWithExchange(ctx, exchange)
	return nil
}

// newLines is what a header value has to lose before it is written out. Add
// stores name and value verbatim, unlike Set, so a line break in either would
// end the header line early and let the rest of the value pass for header lines
// of its own, or for a second response.
var newLines = strings.NewReplacer("\r", " ", "\n", " ")

func populateResponseWithExchange(ctx *fiber.Ctx, exchange *model.Exchange) {
	if exchange == nil {
		return
	}
	response := ctx.Response()
	for name, values := range exchange.Headers {
		// A saved mock cannot carry a line break in a header any more, but a row
		// saved before that was enforced still can. Its value is repaired the way
		// Set repairs one; its name is dropped, since a name with a line break in
		// it names no header a client would read.
		if strings.ContainsAny(name, "\r\n") {
			continue
		}
		for _, value := range values {
			response.Header.Add(name, newLines.Replace(value))
		}
	}
	response.SetBodyRaw(exchange.Body)
	ctx.Status(exchange.Status)
}

func buildRequestExchange(ctx *fiber.Ctx) model.Exchange {
	return model.Exchange{Body: ctx.Request().Body(), Headers: ctx.GetReqHeaders()}
}
