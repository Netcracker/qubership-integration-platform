package dao

import (
	"context"
	"log/slog"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func GetTestsRunsSortingFields() *[]string {
	return &[]string{"id", "start", "finish", "status", "errors", "test_cases", "created_by", "created_at"}
}

func GetTestsRunsFilterConfiguration() *model.FilterConfiguration {
	return &model.FilterConfiguration{
		"id":         GetIdFilterConfiguration("tests_run_view.id"),
		"chain_id":   GetStringFilterConfiguration("trigger_reference.chain_id"),
		"start":      GetTimestampFilterConfiguration("tests_run_view.start"),
		"finish":     GetTimestampFilterConfiguration("tests_run_view.finish"),
		"status":     GetEnumFilterConfiguration("tests_run_view.status"),
		"errors":     GetIntegerFilterConfiguration("tests_run_view.errors"),
		"test_cases": GetIntegerFilterConfiguration("tests_run_view.test_cases"),
		"created_by": GetStringFilterConfiguration("tests_run_view.created_by"),
		"created_at": GetTimestampFilterConfiguration("tests_run_view.created_at"),
	}
}

type TestsRunsRepository interface {
	FindAll(
		ctx context.Context,
		specification *model.SelectionSpecification,
		sorting model.SortOptions,
		pagination *model.PaginationOptions,
	) (*[]TestsRunView, error)
	FindById(ctx context.Context, id uuid.UUID) (*TestsRunView, error)
	Insert(ctx context.Context, testsRun *TestsRun) error
	Delete(ctx context.Context, id uuid.UUID) error
	BulkDelete(ctx context.Context, ids *[]uuid.UUID) error
}

type testsRunsRepository struct {
	logger          *slog.Logger
	paginationLimit int
}

func NewTestsRunsRepository(logger *slog.Logger, paginationLimit int) TestsRunsRepository {
	return &testsRunsRepository{logger: logger, paginationLimit: paginationLimit}
}

func (r *testsRunsRepository) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
) (*[]TestsRunView, error) {
	if err := ValidateSortOptions(sorting, GetTestsRunsSortingFields()); err != nil {
		return nil, err
	}

	filterConfiguration := GetTestsRunsFilterConfiguration()
	if specification != nil {
		if err := ValidateFilters(specification.Filters, filterConfiguration); err != nil {
			return nil, err
		}
	}

	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []TestsRunView

	query := db.NewSelect().Model(&result)

	// The chain id lives two joins away, so only the queries that name it pay
	// for the joins and the resulting DISTINCT.
	if specification != nil && (specification.SearchText != nil || HasFeatureFilter(specification, "chain_id")) {
		query = query.
			Join("LEFT JOIN test_case_runs AS test_case_run").JoinOn("test_case_run.tests_run_id = tests_run_view.id").
			Join("LEFT JOIN trigger_references trigger_reference").JoinOn("trigger_reference.test_case_id = test_case_run.test_case_id").
			Distinct()
	}

	query = query.ApplyQueryBuilder(func(builder bun.QueryBuilder) bun.QueryBuilder {
		return AddSpecification(builder, specification, filterConfiguration)
	})

	query = AddSorting(query, sorting)
	if pagination != nil {
		query = AddPagination(query, *pagination, r.paginationLimit)
	}

	if r.logger.Enabled(ctx, slog.LevelDebug) {
		r.logger.DebugContext(ctx, "Selecting test runs", "query", query.String())
	}

	if err := query.Scan(ctx); err != nil {
		return nil, err
	}
	if result == nil {
		result = []TestsRunView{}
	}
	return &result, nil
}

func (r *testsRunsRepository) FindById(ctx context.Context, id uuid.UUID) (*TestsRunView, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []TestsRunView
	if err := db.NewSelect().Model(&result).Where("id = ?", id).Scan(ctx); err != nil {
		return nil, err
	}
	if len(result) == 0 {
		return nil, nil
	}
	return &result[0], nil
}

func (r *testsRunsRepository) Insert(ctx context.Context, testsRun *TestsRun) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewInsert().Model(testsRun).Exec(ctx)
	return err
}

func (r *testsRunsRepository) Delete(ctx context.Context, id uuid.UUID) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*TestsRun)(nil)).Where("id = ?", id).Exec(ctx)
	return err
}

func (r *testsRunsRepository) BulkDelete(ctx context.Context, ids *[]uuid.UUID) error {
	if ids == nil || len(*ids) == 0 {
		return nil
	}
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*TestsRun)(nil)).Where("id IN (?)", bun.In(*ids)).Exec(ctx)
	return err
}
