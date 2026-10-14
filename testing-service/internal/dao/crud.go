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

// One interface per statement, so a field in Repositories names exactly the
// methods its caller uses and nothing else.

type Inserter[T any] interface {
	Insert(ctx context.Context, row *T) (*T, error)
}

type BulkInserter[T any] interface {
	BulkInsert(ctx context.Context, rows *[]T) error
}

type Updater[T any] interface {
	Update(ctx context.Context, row *T) error
}

type Deleter[T any] interface {
	Delete(ctx context.Context, id uuid.UUID) error
}

type InsertDeleter[T any] interface {
	Inserter[T]
	Deleter[T]
}

type InsertUpdateDeleter[T any] interface {
	Inserter[T]
	Updater[T]
	Deleter[T]
}

// crudRepository implements all of them; the field it is assigned to picks the
// method set the caller sees.
type crudRepository[T any] struct{}

func NewCrudRepository[T any]() *crudRepository[T] {
	return &crudRepository[T]{}
}

func (r *crudRepository[T]) Insert(ctx context.Context, row *T) (*T, error) {
	return insertRow(ctx, row)
}

func (r *crudRepository[T]) BulkInsert(ctx context.Context, rows *[]T) error {
	return bulkInsert(ctx, rows)
}

func (r *crudRepository[T]) Update(ctx context.Context, row *T) error {
	return updateRow(ctx, row)
}

func (r *crudRepository[T]) Delete(ctx context.Context, id uuid.UUID) error {
	return deleteRow[T](ctx, id)
}

// The four method sets Repositories asks for.
var (
	_ BulkInserter[Header]                   = (*crudRepository[Header])(nil)
	_ Inserter[Message]                      = (*crudRepository[Message])(nil)
	_ InsertDeleter[RequestSettings]         = (*crudRepository[RequestSettings])(nil)
	_ InsertUpdateDeleter[EndpointReference] = (*crudRepository[EndpointReference])(nil)
)
