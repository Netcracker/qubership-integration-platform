package dao

import (
	"context"
	"log/slog"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// Shared and read-only; see GetTestCaseRunsSortingFields.
var (
	endpointMocksSortingFields = []string{
		"id",
		"name",
		"description",
		"chain_id",
		"element_id",
		"enabled",
		"status",
		"delay",
		"created_by",
		"created_at",
		"updated_by",
		"updated_at",
	}

	endpointMocksFilterConfiguration = model.FilterConfiguration{
		"id":          GetIdFilterConfiguration("endpoint_mock.id"),
		"name":        GetStringFilterConfiguration("endpoint_mock.name"),
		"description": GetStringFilterConfiguration("endpoint_mock.description"),
		"chain_id":    GetStringFilterConfiguration("endpoint_reference.chain_id"),
		"element_id":  GetStringFilterConfiguration("endpoint_reference.element_id"),
		"enabled":     GetBooleanFilterConfiguration("endpoint_mock.enabled"),
		"status":      GetIntegerWithSubstringFilterConfiguration("response_settings.status"),
		"delay":       GetIntegerFilterConfiguration("response_settings.delay"),
		"created_by":  GetStringFilterConfiguration("endpoint_mock.created_by"),
		"created_at":  GetTimestampFilterConfiguration("endpoint_mock.created_at"),
		"updated_by":  GetStringFilterConfiguration("endpoint_mock.updated_by"),
		"updated_at":  GetTimestampFilterConfiguration("endpoint_mock.updated_at"),
	}
)

func GetEndpointMocksSortingFields() *[]string {
	return &endpointMocksSortingFields
}

func GetEndpointMocksFilterConfiguration() *model.FilterConfiguration {
	return &endpointMocksFilterConfiguration
}

type EndpointMocksRepository interface {
	FindAll(
		ctx context.Context,
		specification *model.SelectionSpecification,
		sorting model.SortOptions,
		pagination *model.PaginationOptions,
		withRelations bool,
	) (*[]EndpointMock, error)
	FindById(ctx context.Context, id uuid.UUID, withRelations bool) (*EndpointMock, error)
	Insert(ctx context.Context, endpointMock *EndpointMock) (*EndpointMock, error)
	Delete(ctx context.Context, id uuid.UUID) error
	BulkDelete(ctx context.Context, ids *[]uuid.UUID) error
	Exists(ctx context.Context, id uuid.UUID) (bool, error)
	Update(ctx context.Context, endpointMock *EndpointMock) error
}

type endpointMocksRepository struct {
	logger          *slog.Logger
	paginationLimit int
}

func NewEndpointMocksRepository(logger *slog.Logger, paginationLimit int) EndpointMocksRepository {
	return &endpointMocksRepository{logger: logger, paginationLimit: paginationLimit}
}

func withEndpointMockRelations(query *bun.SelectQuery) *bun.SelectQuery {
	return query.
		Relation("EndpointReference").
		Relation("ResponseSettings").
		Relation("ResponseSettings.Message").
		Relation("ResponseSettings.Message.Headers").
		Relation("RequestMatchers").
		Relation("RequestMatchers.Parameters")
}

func (r *endpointMocksRepository) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
	_ bool,
) (*[]EndpointMock, error) {
	return listing[EndpointMock]{
		logger:          r.logger,
		paginationLimit: r.paginationLimit,
		subject:         "endpoint mocks",
		sortingFields:   GetEndpointMocksSortingFields(),
		filters:         GetEndpointMocksFilterConfiguration(),
		// The relations carry the columns the filters name, so the list query joins
		// them whatever withRelations says.
		decorate: func(query *bun.SelectQuery, _ *model.SelectionSpecification) *bun.SelectQuery {
			return withEndpointMockRelations(query)
		},
	}.run(ctx, specification, sorting, pagination)
}

func (r *endpointMocksRepository) FindById(ctx context.Context, id uuid.UUID, withRelations bool) (*EndpointMock, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []EndpointMock
	query := db.NewSelect().Model(&result)
	if withRelations {
		query = withEndpointMockRelations(query)
	}
	if err := query.Where("endpoint_mock.id = ?", id).Scan(ctx); err != nil {
		return nil, err
	}
	if len(result) == 0 {
		return nil, nil
	}
	return &result[0], nil
}

func (r *endpointMocksRepository) Delete(ctx context.Context, id uuid.UUID) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*EndpointMock)(nil)).Where("id = ?", id).Exec(ctx)
	return err
}

func (r *endpointMocksRepository) BulkDelete(ctx context.Context, ids *[]uuid.UUID) error {
	if ids == nil || len(*ids) == 0 {
		return nil
	}
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*EndpointMock)(nil)).Where("id IN (?)", bun.In(*ids)).Exec(ctx)
	return err
}

func (r *endpointMocksRepository) Insert(ctx context.Context, endpointMock *EndpointMock) (*EndpointMock, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result EndpointMock
	if _, err := db.NewInsert().Model(endpointMock).Returning("*").Exec(ctx, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

func (r *endpointMocksRepository) Exists(ctx context.Context, id uuid.UUID) (bool, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return false, err
	}
	return db.NewSelect().Model((*EndpointMock)(nil)).Where("id = ?", id).Exists(ctx)
}

// Update writes the whole row apart from the creation audit. The model is built
// from the request body and the audit hook refreshes only the updated_* pair, so
// a body that omits createdAt would otherwise null the stored value. The Call
// path orders the candidate mocks by created_at, and a null sorts first, so the
// updated mock would also jump ahead of older ones.
func (r *endpointMocksRepository) Update(ctx context.Context, endpointMock *EndpointMock) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewUpdate().Model(endpointMock).ExcludeColumn("created_at", "created_by").WherePK().Exec(ctx)
	return err
}
