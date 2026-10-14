package dao

import (
	"context"
	"time"

	"github.com/uptrace/bun"
)

// DefaultUser is recorded when no user reached the context: a host that installed
// no middleware, or a write started outside a request.
const DefaultUser = "developer"

// userContextKey addresses the user the audit hook stamps on writes.
type userContextKey struct{}

var _ bun.BeforeAppendModelHook = (*Metadata)(nil)

// WithCurrentUser returns a copy of ctx carrying the user for audited writes.
func WithCurrentUser(ctx context.Context, user string) context.Context {
	return context.WithValue(ctx, userContextKey{}, user)
}

// CurrentUser returns the user WithCurrentUser stored in ctx, or DefaultUser.
func CurrentUser(ctx context.Context) string {
	user, ok := ctx.Value(userContextKey{}).(string)
	if !ok || user == "" {
		return DefaultUser
	}
	return user
}

// BeforeAppendModel stamps the audit columns. bun constructs the model, so the
// user cannot be injected here and is resolved from the context instead.
func (t *Metadata) BeforeAppendModel(ctx context.Context, query bun.Query) error {
	switch query.(type) {
	case *bun.InsertQuery:
		userName := CurrentUser(ctx)
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
		userName := CurrentUser(ctx)
		timestamp := time.Now()
		t.UpdatedAt = &timestamp
		t.UpdatedBy = &userName
	}
	return nil
}
