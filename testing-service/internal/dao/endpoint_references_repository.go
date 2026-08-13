package dao

import (
	"context"

	"github.com/google/uuid"
)

type EndpointReferencesRepository interface {
	Insert(ctx context.Context, endpointReference *EndpointReference) (*EndpointReference, error)
	Delete(ctx context.Context, id uuid.UUID) error
	Update(ctx context.Context, endpointReference *EndpointReference) error
}

type endpointReferencesRepository struct{}

func NewEndpointReferencesRepository() EndpointReferencesRepository {
	return &endpointReferencesRepository{}
}

func (r *endpointReferencesRepository) Insert(ctx context.Context, endpointReference *EndpointReference) (*EndpointReference, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result EndpointReference
	if _, err := db.NewInsert().Model(endpointReference).Returning("*").Exec(ctx, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

func (r *endpointReferencesRepository) Delete(ctx context.Context, id uuid.UUID) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*EndpointReference)(nil)).Where("id = ?", id).Exec(ctx)
	return err
}

func (r *endpointReferencesRepository) Update(ctx context.Context, endpointReference *EndpointReference) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewUpdate().Model(endpointReference).WherePK().Exec(ctx)
	return err
}
