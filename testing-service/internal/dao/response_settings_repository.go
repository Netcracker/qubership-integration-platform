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
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result ResponseSettings
	if _, err := db.NewInsert().Model(responseSettings).Returning("*").Exec(ctx, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

func (r *responseSettingsRepository) Delete(ctx context.Context, id uuid.UUID) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*ResponseSettings)(nil)).Where("id = ?", id).Exec(ctx)
	return err
}
