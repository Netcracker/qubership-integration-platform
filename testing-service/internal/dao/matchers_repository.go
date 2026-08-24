package dao

import (
	"context"

	"github.com/google/uuid"
	"github.com/uptrace/bun"
)

type MatchersRepository interface {
	Insert(ctx context.Context, matcher *Matcher) (*Matcher, error)
	DeleteByOwnerId(ctx context.Context, id uuid.UUID) error
	DeleteByOwnerIds(ctx context.Context, ids *[]uuid.UUID) error
}

type matchersRepository struct{}

func NewMatchersRepository() MatchersRepository {
	return &matchersRepository{}
}

func (r *matchersRepository) Insert(ctx context.Context, matcher *Matcher) (*Matcher, error) {
	return insertRow(ctx, matcher)
}

func (r *matchersRepository) DeleteByOwnerId(ctx context.Context, id uuid.UUID) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*Matcher)(nil)).Where("owner_id = ?", id).Exec(ctx)
	return err
}

func (r *matchersRepository) DeleteByOwnerIds(ctx context.Context, ids *[]uuid.UUID) error {
	if ids == nil || len(*ids) == 0 {
		return nil
	}
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewDelete().Model((*Matcher)(nil)).Where("owner_id IN (?)", bun.In(*ids)).Exec(ctx)
	return err
}
