package controllers

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// ServiceName is reported in the serviceName field of every error body.
const ServiceName = "testing-service"

// Content types the export endpoints answer with.
const (
	MIMEApplicationZIP = "application/zip"
	MIMETextCSV        = "text/csv"
)

// ErrorMessage is the body of a failed request.
type ErrorMessage struct {
	// Name of the service that produced the message.
	ServiceName string `json:"serviceName" example:"testing-service"`

	// The error itself, which may be a custom message or an internal failure.
	ErrorMessage string `json:"errorMessage" example:"Something went wrong."`

	// The stack trace, when one is available; otherwise "No Stacktrace Available".
	Stacktrace string `json:"stacktrace" example:"No Stacktrace Available"`

	// Time of the error, formatted as yyyy-mm-dd hh:mm:ss.mmm.
	ErrorDate string `json:"errorDate" example:"2024-05-17 11:33:23.038"`
} // @name ErrorMessage

// responder holds what the response helpers need beyond the fiber context. Every
// controller embeds it.
type responder struct {
	logger *slog.Logger
}

func (r responder) malformedUUID(ctx *fiber.Ctx, value string, err error) error {
	return r.fail(ctx, fiber.StatusBadRequest, "Failed to convert string \"%v\" to UUID: %v", value, err.Error())
}

func (r responder) malformedRequestBody(ctx *fiber.Ctx, err error) error {
	return r.fail(ctx, fiber.StatusBadRequest, "Malformed request body: %v", err.Error())
}

func (r responder) malformedSortingParameters(ctx *fiber.Ctx, err error) error {
	return r.fail(ctx, fiber.StatusBadRequest, "Malformed sorting query parameters: %v", err.Error())
}

func (r responder) malformedPaginationParameters(ctx *fiber.Ctx, err error) error {
	return r.fail(ctx, fiber.StatusBadRequest, "Malformed pagination query parameters: %v", err.Error())
}

// malformedSelection answers a filter or sorting value the listing refused. The
// detail names the value and what the listing accepts instead, and it is the
// caller's own input, so it belongs in the body.
func (r responder) malformedSelection(ctx *fiber.Ctx, err error) error {
	return r.fail(ctx, fiber.StatusBadRequest, "Malformed selection parameters: %v", err.Error())
}

func (r responder) fail(ctx *fiber.Ctx, code int, messageTemplate string, args ...any) error {
	message := fmt.Sprintf(messageTemplate, args...)
	r.logger.ErrorContext(ctx.UserContext(), message, "status", code, "path", ctx.Path())
	return ctx.Status(code).JSON(buildErrorMessage(message))
}

// internalError names the operation that failed and keeps the failure itself in
// the log. The failures behind a 500 are bun and PostgreSQL messages and
// upstream URLs, none of which the caller has any use for.
func (r responder) internalError(ctx *fiber.Ctx, message string, err error) error {
	r.logger.ErrorContext(ctx.UserContext(), message,
		"status", fiber.StatusInternalServerError, "path", ctx.Path(), "error", err)
	return ctx.Status(fiber.StatusInternalServerError).JSON(buildErrorMessage(message))
}

// findAll answers a list request. Every collection reads the same pagination,
// sorting and specification off the request and answers either the rows or, when
// return_ids is set, the unpaginated list of their ids; query and id are all that
// differ between them.
func findAll[T any](
	ctx *fiber.Ctx,
	r responder,
	subject string,
	query func(
		ctx context.Context,
		specification *model.SelectionSpecification,
		sorting model.SortOptions,
		pagination *model.PaginationOptions,
		withRelations bool,
	) (*[]T, error),
	id func(T) uuid.UUID,
) error {
	pagination, err := paginationOptions(ctx)
	if err != nil {
		return r.malformedPaginationParameters(ctx, err)
	}
	sorting, err := sortOptions(ctx)
	if err != nil {
		return r.malformedSortingParameters(ctx, err)
	}
	var specification model.SelectionSpecification
	if err := json.Unmarshal(ctx.Body(), &specification); err != nil {
		return r.malformedRequestBody(ctx, err)
	}
	returnIds := ctx.QueryBool("return_ids", false)
	if returnIds {
		pagination = nil
	}
	rows, err := query(ctx.UserContext(), &specification, *sorting, pagination, !returnIds)
	if err != nil {
		// A filter or sorting value the listing does not accept is a bad request,
		// not a failure of this service.
		if errors.Is(err, dao.ErrInvalidSelection) {
			return r.malformedSelection(ctx, err)
		}
		return r.internalError(ctx, "Unable to get "+subject, err)
	}
	if !returnIds {
		return ctx.Status(fiber.StatusOK).JSON(rows)
	}
	ids := make([]uuid.UUID, 0, len(*rows))
	for _, row := range *rows {
		ids = append(ids, id(row))
	}
	return ctx.Status(fiber.StatusOK).JSON(ids)
}

// findByID answers a read-by-id request. It takes the subject twice because the
// two messages place it differently: the failure names it mid-sentence, the
// not-found message opens with it.
func findByID[T any](
	ctx *fiber.Ctx,
	r responder,
	subject string,
	capitalizedSubject string,
	query func(ctx context.Context, id uuid.UUID) (*T, error),
) error {
	idString := ctx.Params("id")
	id, err := uuid.Parse(idString)
	if err != nil {
		return r.malformedUUID(ctx, idString, err)
	}
	row, err := query(ctx.UserContext(), id)
	if err != nil {
		return r.internalError(ctx, "Unable to get "+subject+" by ID", err)
	}
	if row == nil {
		return r.fail(ctx, fiber.StatusNotFound, "%s %v not found.", capitalizedSubject, id)
	}
	return ctx.Status(fiber.StatusOK).JSON(row)
}

// actOnID runs an action over the id on the path and answers 204. Delete and
// cancel report nothing but failure, so the action and the message are all that
// differ between them.
func (r responder) actOnID(
	ctx *fiber.Ctx,
	message string,
	action func(ctx context.Context, id uuid.UUID) error,
) error {
	idString := ctx.Params("id")
	id, err := uuid.Parse(idString)
	if err != nil {
		return r.malformedUUID(ctx, idString, err)
	}
	if err := action(ctx.UserContext(), id); err != nil {
		return r.internalError(ctx, message, err)
	}
	return ctx.SendStatus(fiber.StatusNoContent)
}

// actOnIDs is actOnID over the list of ids in the body.
func (r responder) actOnIDs(
	ctx *fiber.Ctx,
	message string,
	action func(ctx context.Context, ids *[]uuid.UUID) error,
) error {
	var ids []uuid.UUID
	if err := json.Unmarshal(ctx.Body(), &ids); err != nil {
		return r.malformedRequestBody(ctx, err)
	}
	if err := action(ctx.UserContext(), &ids); err != nil {
		return r.internalError(ctx, message, err)
	}
	return ctx.SendStatus(fiber.StatusNoContent)
}

func respondWithZip(ctx *fiber.Ctx, code int, payload []byte) error {
	response := ctx.Response()
	response.SetBodyRaw(payload)
	response.Header.SetContentType(MIMEApplicationZIP)
	ctx.Status(code)
	return nil
}

func respondWithCsv(ctx *fiber.Ctx, code int, payload string) error {
	ctx.Response().Header.SetContentType(MIMETextCSV)
	return ctx.Status(code).SendString(payload)
}

func buildErrorMessage(message string) ErrorMessage {
	return ErrorMessage{
		ServiceName:  ServiceName,
		ErrorMessage: message,
		Stacktrace:   "No Stacktrace Available",
		ErrorDate:    time.Now().UTC().Format("2006-01-02 15:04:05.000"),
	}
}
