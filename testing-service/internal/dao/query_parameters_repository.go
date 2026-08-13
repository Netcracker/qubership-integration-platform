package dao

import "context"

type QueryParametersRepository interface {
	BulkInsert(ctx context.Context, params *[]QueryParameter) error
}

type queryParametersRepository struct{}

func NewQueryParametersRepository() QueryParametersRepository {
	return &queryParametersRepository{}
}

func (r *queryParametersRepository) BulkInsert(ctx context.Context, params *[]QueryParameter) error {
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
