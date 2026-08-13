package dao

import (
	"context"
	"time"

	"github.com/uptrace/bun"
)

var _ bun.BeforeAppendModelHook = (*Metadata)(nil)

// currentUser names the author recorded on audited writes. Task 4 replaces it
// with the request-context seam.
func currentUser(_ context.Context) string {
	return "developer"
}

// BeforeAppendModel stamps the audit columns. bun constructs the model, so the
// user cannot be injected here and is resolved from the context instead.
func (t *Metadata) BeforeAppendModel(ctx context.Context, query bun.Query) error {
	switch query.(type) {
	case *bun.InsertQuery:
		userName := currentUser(ctx)
		timestamp := time.Now()
		if t.CreatedAt == nil {
			t.CreatedAt = &timestamp
		}
		if t.CreatedBy == nil {
			t.CreatedBy = &userName
		}
		t.UpdatedAt = &timestamp
		t.UpdatedBy = &userName
	case *bun.UpdateQuery:
		userName := currentUser(ctx)
		timestamp := time.Now()
		t.UpdatedAt = &timestamp
		t.UpdatedBy = &userName
	}
	return nil
}
