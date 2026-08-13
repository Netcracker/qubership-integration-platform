package dao

import "context"

type MatcherParametersRepository interface {
	BulkInsert(ctx context.Context, params *[]MatcherParameter) error
}

type matcherParametersRepository struct{}

func NewMatcherParametersRepository() MatcherParametersRepository {
	return &matcherParametersRepository{}
}

func (r *matcherParametersRepository) BulkInsert(ctx context.Context, params *[]MatcherParameter) error {
	if params == nil || len(*params) == 0 {
		return nil
	}
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewInsert().Model(params).Exec(ctx)
	return err
}
