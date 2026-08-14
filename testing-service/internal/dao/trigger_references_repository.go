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
	return insertRow(ctx, triggerReference)
}

func (r *triggerReferencesRepository) Delete(ctx context.Context, id uuid.UUID) error {
	return deleteRow[TriggerReference](ctx, id)
}

func (r *triggerReferencesRepository) Update(ctx context.Context, triggerReference *TriggerReference) error {
	return updateRow(ctx, triggerReference)
}
