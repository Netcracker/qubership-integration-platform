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
	return bulkInsert(ctx, headers)
}
