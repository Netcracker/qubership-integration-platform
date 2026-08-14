package dao

import (
	"context"

	"github.com/google/uuid"
)

type RequestSettingsRepository interface {
	Insert(ctx context.Context, requestSettings *RequestSettings) (*RequestSettings, error)
	Delete(ctx context.Context, id uuid.UUID) error
}

type requestSettingsRepository struct{}

func NewRequestSettingsRepository() RequestSettingsRepository {
	return &requestSettingsRepository{}
}

func (r *requestSettingsRepository) Insert(ctx context.Context, requestSettings *RequestSettings) (*RequestSettings, error) {
	return insertRow(ctx, requestSettings)
}

func (r *requestSettingsRepository) Delete(ctx context.Context, id uuid.UUID) error {
	return deleteRow[RequestSettings](ctx, id)
}
