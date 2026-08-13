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
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result RequestSettings
	if _, err := db.NewInsert().Model(requestSettings).Returning("*").Exec(ctx, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

func (r *requestSettingsRepository) Delete(ctx context.Context, id uuid.UUID) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*RequestSettings)(nil)).Where("id = ?", id).Exec(ctx)
	return err
}
