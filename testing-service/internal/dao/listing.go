package dao

import (
	"context"
	"log/slog"

	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// listing is the shape every FindAll has: narrow the query by the selection,
// order it, cut it into a page, and answer an empty slice rather than a nil one.
// The four listings differ only in the model, the joins and the tables that
// describe them, so each repository declares those and this runs them.
type listing[T any] struct {
	logger          *slog.Logger
	paginationLimit int
	// subject names the rows in the debug log.
	subject string
	// sortingFields and filters are the shared, read-only tables of the listing.
	sortingFields *[]string
	filters       *model.FilterConfiguration
	// decorate adds the relations and joins the listing needs. It sees the
	// selection, because a join only some requests pay for depends on it.
	decorate func(*bun.SelectQuery, *model.SelectionSpecification) *bun.SelectQuery
}

func (l listing[T]) run(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
) (*[]T, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []T

	query := db.NewSelect().Model(&result)
	if l.decorate != nil {
		query = l.decorate(query, specification)
	}

	query, err = ApplySpecification(query, specification, l.filters)
	if err != nil {
		return nil, err
	}
	query, err = AddSorting(query, sorting, l.sortingFields)
	if err != nil {
		return nil, err
	}
	if pagination != nil {
		query = AddPagination(query, *pagination, l.paginationLimit)
	}

	if l.logger.Enabled(ctx, slog.LevelDebug) {
		l.logger.DebugContext(ctx, "Selecting "+l.subject, "query", query.String())
	}

	if err := query.Scan(ctx); err != nil {
		return nil, err
	}
	if result == nil {
		result = []T{}
	}
	return &result, nil
}
