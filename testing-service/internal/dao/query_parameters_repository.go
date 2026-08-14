package dao

import "context"

type QueryParametersRepository interface {
	BulkInsert(ctx context.Context, parameters *[]QueryParameter) error
}

type queryParametersRepository struct{}

func NewQueryParametersRepository() QueryParametersRepository {
	return &queryParametersRepository{}
}

func (r *queryParametersRepository) BulkInsert(ctx context.Context, parameters *[]QueryParameter) error {
	return bulkInsert(ctx, parameters)
}
