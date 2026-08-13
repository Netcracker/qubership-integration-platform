package dao

import "context"

type HeadersRepository interface {
	BulkInsert(ctx context.Context, headers *[]Header) error
}

type headersRepository struct{}

func NewHeadersRepository() HeadersRepository {
	return &headersRepository{}
}

func (r *headersRepository) BulkInsert(ctx context.Context, headers *[]Header) error {
	if headers == nil || len(*headers) == 0 {
		return nil
	}
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewInsert().Model(headers).Exec(ctx)
	return err
}
