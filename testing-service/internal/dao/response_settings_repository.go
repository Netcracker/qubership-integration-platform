package dao

import (
	"context"

	"github.com/google/uuid"
)

type ResponseSettingsRepository interface {
	Insert(ctx context.Context, responseSettings *ResponseSettings) (*ResponseSettings, error)
	Delete(ctx context.Context, id uuid.UUID) error
}

type responseSettingsRepository struct{}

func NewResponseSettingsRepository() ResponseSettingsRepository {
	return &responseSettingsRepository{}
}

func (r *responseSettingsRepository) Insert(ctx context.Context, responseSettings *ResponseSettings) (*ResponseSettings, error) {
	return insertRow(ctx, responseSettings)
}

func (r *responseSettingsRepository) Delete(ctx context.Context, id uuid.UUID) error {
	return deleteRow[ResponseSettings](ctx, id)
}
