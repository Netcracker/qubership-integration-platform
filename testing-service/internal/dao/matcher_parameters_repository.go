package dao

import "context"

type MatcherParametersRepository interface {
	BulkInsert(ctx context.Context, parameters *[]MatcherParameter) error
}

type matcherParametersRepository struct{}

func NewMatcherParametersRepository() MatcherParametersRepository {
	return &matcherParametersRepository{}
}

func (r *matcherParametersRepository) BulkInsert(ctx context.Context, parameters *[]MatcherParameter) error {
	return bulkInsert(ctx, parameters)
}
