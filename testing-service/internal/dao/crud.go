package dao

import (
	"context"

	"github.com/google/uuid"
)

// The statements every child table of a test case or an endpoint mock is written
// with. They differ only in the model, so they are written once here and the
// repositories name the type.

// insertRow stores row and returns it as the database left it, defaults and
// audit columns included.
func insertRow[T any](ctx context.Context, row *T) (*T, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result T
	if _, err := db.NewInsert().Model(row).Returning("*").Exec(ctx, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// bulkInsert stores rows in one statement. An empty batch is not a statement:
// the children of an entity that has none are inserted as an empty slice.
func bulkInsert[T any](ctx context.Context, rows *[]T) error {
	if rows == nil || len(*rows) == 0 {
		return nil
	}
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewInsert().Model(rows).Exec(ctx)
	return err
}

// updateRow writes the columns of row that bun reports, matched on its primary
// key.
func updateRow[T any](ctx context.Context, row *T) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewUpdate().Model(row).WherePK().Exec(ctx)
	return err
}

// deleteRow removes the row of type T with the given id.
func deleteRow[T any](ctx context.Context, id uuid.UUID) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*T)(nil)).Where("id = ?", id).Exec(ctx)
	return err
}
