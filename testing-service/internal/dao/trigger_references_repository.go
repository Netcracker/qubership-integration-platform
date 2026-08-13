package dao

import (
	"context"

	"github.com/google/uuid"
)

type TriggerReferencesRepository interface {
	Insert(ctx context.Context, triggerReference *TriggerReference) (*TriggerReference, error)
	Delete(ctx context.Context, id uuid.UUID) error
	Update(ctx context.Context, triggerReference *TriggerReference) error
}

type triggerReferencesRepository struct{}

func NewTriggerReferencesRepository() TriggerReferencesRepository {
	return &triggerReferencesRepository{}
}

func (r *triggerReferencesRepository) Insert(ctx context.Context, triggerReference *TriggerReference) (*TriggerReference, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result TriggerReference
	if _, err := db.NewInsert().Model(triggerReference).Returning("*").Exec(ctx, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

func (r *triggerReferencesRepository) Delete(ctx context.Context, id uuid.UUID) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*TriggerReference)(nil)).Where("id = ?", id).Exec(ctx)
	return err
}

func (r *triggerReferencesRepository) Update(ctx context.Context, triggerReference *TriggerReference) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewUpdate().Model(triggerReference).WherePK().Exec(ctx)
	return err
}
