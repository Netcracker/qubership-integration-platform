package controllers

import (
	"github.com/gofiber/fiber/v2"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func paginationOptions(ctx *fiber.Ctx) (*model.PaginationOptions, error) {
	options := &model.PaginationOptions{}
	if err := ctx.QueryParser(options); err != nil {
		return nil, err
	}
	return options, nil
}

func sortOptions(ctx *fiber.Ctx) (*model.SortOptions, error) {
	options := &model.SortOptions{}
	if err := ctx.QueryParser(options); err != nil {
		return nil, err
	}
	if options.Order == "" {
		options.Order = model.OrderAscending
	}
	return options, nil
}
