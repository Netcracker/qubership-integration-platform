package dao

import "context"

type PathParametersRepository interface {
	BulkInsert(ctx context.Context, parameters *[]PathParameter) error
}

type pathParametersRepository struct{}

func NewPathParametersRepository() PathParametersRepository {
	return &pathParametersRepository{}
}

func (r *pathParametersRepository) BulkInsert(ctx context.Context, parameters *[]PathParameter) error {
	return bulkInsert(ctx, parameters)
}
