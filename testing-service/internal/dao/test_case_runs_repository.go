package dao

import (
	"context"
	"log/slog"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func GetTestCaseRunsSortingFields() *[]string {
	return &[]string{"id", "test_case_name", "chain_id", "start", "finish", "status", "errors"}
}

func GetTestCaseRunsFilterConfiguration() *model.FilterConfiguration {
	return &model.FilterConfiguration{
		"id":             GetIdFilterConfiguration("test_case_run_view.id"),
		"test_case_id":   GetIdFilterConfiguration("test_case_run_view.test_case_id"),
		"test_case_name": GetStringFilterConfiguration("test_case_run_view.test_case_name"),
		"tests_run_id":   GetIdFilterConfiguration("test_case_run_view.tests_run_id"),
		"chain_id":       GetStringFilterConfiguration("test_case_run_view.chain_id"),
		"start":          GetTimestampFilterConfiguration("test_case_run_view.start"),
		"finish":         GetTimestampFilterConfiguration("test_case_run_view.finish"),
		"status":         GetEnumFilterConfiguration("test_case_run_view.status"),
		"errors":         GetIntegerFilterConfiguration("test_case_run_view.errors"),
	}
}

type TestCaseRunsRepository interface {
	FindAll(
		ctx context.Context,
		specification *model.SelectionSpecification,
		sorting model.SortOptions,
		pagination *model.PaginationOptions,
	) (*[]TestCaseRunView, error)
	FindById(ctx context.Context, id uuid.UUID) (*TestCaseRunView, error)
	Insert(ctx context.Context, testCaseRuns *[]TestCaseRun) error
	Update(ctx context.Context, testCaseRun *TestCaseRun, omitZero bool) error
	UpdateStatus(ctx context.Context, selector func(bun.QueryBuilder) bun.QueryBuilder, status string) error
	FindPending(ctx context.Context) (*TestCaseRun, error)
}

type testCaseRunsRepository struct {
	logger          *slog.Logger
	paginationLimit int
}

func NewTestCaseRunsRepository(logger *slog.Logger, paginationLimit int) TestCaseRunsRepository {
	return &testCaseRunsRepository{logger: logger, paginationLimit: paginationLimit}
}

func (r *testCaseRunsRepository) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
) (*[]TestCaseRunView, error) {
	if err := ValidateSortOptions(sorting, GetTestCaseRunsSortingFields()); err != nil {
		return nil, err
	}

	filterConfiguration := GetTestCaseRunsFilterConfiguration()
	if specification != nil {
		if err := ValidateFilters(specification.Filters, filterConfiguration); err != nil {
			return nil, err
		}
	}

	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []TestCaseRunView

	query := db.NewSelect().Model(&result).ApplyQueryBuilder(func(builder bun.QueryBuilder) bun.QueryBuilder {
		return AddSpecification(builder, specification, filterConfiguration)
	})

	query = AddSorting(query, sorting)
	if pagination != nil {
		query = AddPagination(query, *pagination, r.paginationLimit)
	}

	if r.logger.Enabled(ctx, slog.LevelDebug) {
		r.logger.DebugContext(ctx, "Selecting test case runs", "query", query.String())
	}

	if err := query.Scan(ctx); err != nil {
		return nil, err
	}
	if result == nil {
		result = []TestCaseRunView{}
	}
	return &result, nil
}

func (r *testCaseRunsRepository) FindById(ctx context.Context, id uuid.UUID) (*TestCaseRunView, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []TestCaseRunView
	if err := db.NewSelect().Model(&result).Where("id = ?", id).Scan(ctx); err != nil {
		return nil, err
	}
	if len(result) == 0 {
		return nil, nil
	}
	return &result[0], nil
}

func (r *testCaseRunsRepository) Insert(ctx context.Context, testCaseRuns *[]TestCaseRun) error {
	if testCaseRuns == nil || len(*testCaseRuns) == 0 {
		return nil
	}
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewInsert().Model(testCaseRuns).Exec(ctx)
	return err
}

func (r *testCaseRunsRepository) Update(ctx context.Context, testCaseRun *TestCaseRun, omitZero bool) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	query := db.NewUpdate().Model(testCaseRun)
	if omitZero {
		query = query.OmitZero()
	}
	_, err = query.WherePK().Exec(ctx)
	return err
}

func (r *testCaseRunsRepository) UpdateStatus(ctx context.Context, selector func(bun.QueryBuilder) bun.QueryBuilder, status string) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	testCaseRun := TestCaseRun{Status: &status}
	_, err = db.NewUpdate().Model(&testCaseRun).Column("status").ApplyQueryBuilder(selector).Exec(ctx)
	return err
}

func (r *testCaseRunsRepository) FindPending(ctx context.Context) (*TestCaseRun, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []TestCaseRun
	err = db.NewSelect().Model(&result).Where("status = ?", RunStatusPending).Limit(1).Scan(ctx)
	if err != nil {
		return nil, err
	}
	if len(result) == 0 {
		return nil, nil
	}
	return &result[0], nil
}
