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
	return insertRow(ctx, endpointReference)
}

func (r *endpointReferencesRepository) Delete(ctx context.Context, id uuid.UUID) error {
	return deleteRow[EndpointReference](ctx, id)
}

func (r *endpointReferencesRepository) Update(ctx context.Context, endpointReference *EndpointReference) error {
	return updateRow(ctx, endpointReference)
}
