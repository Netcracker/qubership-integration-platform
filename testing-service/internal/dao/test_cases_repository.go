package dao

import (
	"context"
	"log/slog"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func GetTestCasesSortingFields() *[]string {
	return &[]string{
		"id",
		"name",
		"description",
		"enabled",
		"chain_id",
		"element_id",
		"created_by",
		"created_at",
		"updated_by",
		"updated_at",
		"validation_rule_count",
		"enabled_rule_count",
	}
}

func GetTestCasesFilterConfiguration() *model.FilterConfiguration {
	return &model.FilterConfiguration{
		"id":                    GetIdFilterConfiguration("test_case_view.id"),
		"name":                  GetStringFilterConfiguration("test_case_view.name"),
		"description":           GetStringFilterConfiguration("test_case_view.description"),
		"enabled":               GetBooleanFilterConfiguration("test_case_view.enabled"),
		"chain_id":              GetStringFilterConfiguration("test_case_view.chain_id"),
		"element_id":            GetStringFilterConfiguration("test_case_view.element_id"),
		"created_by":            GetStringFilterConfiguration("test_case_view.created_by"),
		"created_at":            GetTimestampFilterConfiguration("test_case_view.created_at"),
		"updated_by":            GetStringFilterConfiguration("test_case_view.updated_by"),
		"updated_at":            GetTimestampFilterConfiguration("test_case_view.updated_at"),
		"validation_rule_count": GetIntegerFilterConfiguration("test_case_view.validation_rule_count"),
		"enabled_rule_count":    GetIntegerFilterConfiguration("test_case_view.enabled_rule_count"),
	}
}

type TestCasesRepository interface {
	FindAll(
		ctx context.Context,
		specification *model.SelectionSpecification,
		sorting model.SortOptions,
		pagination *model.PaginationOptions,
		withRelations bool,
	) (*[]TestCaseView, error)
	FindById(ctx context.Context, id uuid.UUID, withRelations bool) (*TestCaseView, error)
	Insert(ctx context.Context, testCase *TestCase) (*TestCase, error)
	Delete(ctx context.Context, id uuid.UUID) error
	BulkDelete(ctx context.Context, ids *[]uuid.UUID) error
	Exists(ctx context.Context, id uuid.UUID) (bool, error)
	Update(ctx context.Context, testCase *TestCase) error
}

type testCasesRepository struct {
	logger          *slog.Logger
	paginationLimit int
}

func NewTestCasesRepository(logger *slog.Logger, paginationLimit int) TestCasesRepository {
	return &testCasesRepository{logger: logger, paginationLimit: paginationLimit}
}

func withTestCaseRelations(query *bun.SelectQuery) *bun.SelectQuery {
	return query.
		Relation("TriggerReference").
		Relation("RequestSettings").
		Relation("RequestSettings.PathParameters").
		Relation("RequestSettings.QueryParameters").
		Relation("RequestSettings.Message").
		Relation("RequestSettings.Message.Headers").
		Relation("ResponseValidationRules").
		Relation("ResponseValidationRules.Parameters")
}

func (r *testCasesRepository) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
	withRelations bool,
) (*[]TestCaseView, error) {
	if err := ValidateSortOptions(sorting, GetTestCasesSortingFields()); err != nil {
		return nil, err
	}

	filterConfiguration := GetTestCasesFilterConfiguration()
	if specification != nil {
		if err := ValidateFilters(specification.Filters, filterConfiguration); err != nil {
			return nil, err
		}
	}

	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []TestCaseView

	query := db.NewSelect().Model(&result)
	if withRelations {
		query = withTestCaseRelations(query)
	}
	query = query.ApplyQueryBuilder(func(builder bun.QueryBuilder) bun.QueryBuilder {
		return AddSpecification(builder, specification, filterConfiguration)
	})

	query = AddSorting(query, sorting)
	if pagination != nil {
		query = AddPagination(query, *pagination, r.paginationLimit)
	}

	if r.logger.Enabled(ctx, slog.LevelDebug) {
		r.logger.DebugContext(ctx, "Selecting test cases", "query", query.String())
	}

	if err := query.Scan(ctx); err != nil {
		return nil, err
	}
	if result == nil {
		result = []TestCaseView{}
	}
	return &result, nil
}

func (r *testCasesRepository) FindById(ctx context.Context, id uuid.UUID, withRelations bool) (*TestCaseView, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []TestCaseView
	query := db.NewSelect().Model(&result)
	if withRelations {
		query = withTestCaseRelations(query)
	}
	if err := query.Where("test_case_view.id = ?", id).Scan(ctx); err != nil {
		return nil, err
	}
	if len(result) == 0 {
		return nil, nil
	}
	return &result[0], nil
}

func (r *testCasesRepository) Exists(ctx context.Context, id uuid.UUID) (bool, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return false, err
	}
	return db.NewSelect().Model((*TestCase)(nil)).Where("id = ?", id).Exists(ctx)
}

func (r *testCasesRepository) Delete(ctx context.Context, id uuid.UUID) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*TestCase)(nil)).Where("id = ?", id).Exec(ctx)
	return err
}

func (r *testCasesRepository) BulkDelete(ctx context.Context, ids *[]uuid.UUID) error {
	if ids == nil || len(*ids) == 0 {
		return nil
	}
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*TestCase)(nil)).Where("id IN (?)", bun.In(*ids)).Exec(ctx)
	return err
}

func (r *testCasesRepository) Insert(ctx context.Context, testCase *TestCase) (*TestCase, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result TestCase
	if _, err := db.NewInsert().Model(testCase).Returning("*").Exec(ctx, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

func (r *testCasesRepository) Update(ctx context.Context, testCase *TestCase) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewUpdate().Model(testCase).WherePK().Exec(ctx)
	return err
}
