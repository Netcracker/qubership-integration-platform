package dao

import (
	"context"

	"github.com/google/uuid"
	"github.com/uptrace/bun"
)

type TestCaseRunErrorsRepository interface {
	FindByIds(ctx context.Context, ids []uuid.UUID, withMatchers bool) (*[]ValidationError, error)
	FindByTestCaseRunId(ctx context.Context, id uuid.UUID, withMatchers bool) (*[]ValidationError, error)
	Insert(ctx context.Context, validationError *ValidationError) (*ValidationError, error)
}

type testCaseRunErrorsRepository struct{}

func NewTestCaseRunErrorsRepository() TestCaseRunErrorsRepository {
	return &testCaseRunErrorsRepository{}
}

func (r *testCaseRunErrorsRepository) FindByIds(ctx context.Context, ids []uuid.UUID, withMatchers bool) (*[]ValidationError, error) {
	if len(ids) == 0 {
		return &[]ValidationError{}, nil
	}
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []ValidationError
	query := db.NewSelect().Model(&result).Where("validation_error.id IN (?)", bun.In(ids))
	if withMatchers {
		query = query.Relation("Matcher")
	}
	if err := query.Scan(ctx); err != nil {
		return nil, err
	}
	if result == nil {
		result = []ValidationError{}
	}
	return &result, nil
}

func (r *testCaseRunErrorsRepository) FindByTestCaseRunId(ctx context.Context, id uuid.UUID, withMatchers bool) (*[]ValidationError, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []ValidationError
	query := db.NewSelect().Model(&result).Where("test_case_run_id = ?", id)
	if withMatchers {
		query = query.Relation("Matcher")
	}
	if err := query.Scan(ctx); err != nil {
		return nil, err
	}
	if result == nil {
		result = []ValidationError{}
	}
	return &result, nil
}

func (r *testCaseRunErrorsRepository) Insert(ctx context.Context, validationError *ValidationError) (*ValidationError, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result ValidationError
	if _, err := db.NewInsert().Model(validationError).Returning("*").Exec(ctx, &result); err != nil {
		return nil, err
	}
	return &result, nil
}
