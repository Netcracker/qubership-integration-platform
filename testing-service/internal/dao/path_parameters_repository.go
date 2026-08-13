package dao

import "context"

type PathParametersRepository interface {
	BulkInsert(ctx context.Context, params *[]PathParameter) error
}

type pathParametersRepository struct{}

func NewPathParametersRepository() PathParametersRepository {
	return &pathParametersRepository{}
}

func (r *pathParametersRepository) BulkInsert(ctx context.Context, params *[]PathParameter) error {
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
