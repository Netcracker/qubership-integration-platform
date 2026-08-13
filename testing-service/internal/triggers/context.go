package triggers

import (
	"context"
	"errors"
)

// sessionIDKey addresses the session identifier a trigger reports to the engine.
type sessionIDKey struct{}

// WithSessionID returns a copy of ctx carrying the session identifier that
// Activate sends to the engine, which correlates it with the recorded session.
func WithSessionID(ctx context.Context, sessionID string) context.Context {
	return context.WithValue(ctx, sessionIDKey{}, sessionID)
}

// SessionID returns the session identifier WithSessionID stored in ctx.
func SessionID(ctx context.Context) (string, error) {
	sessionID, ok := ctx.Value(sessionIDKey{}).(string)
	if !ok {
		return "", errors.New("session ID is absent from the context")
	}
	return sessionID, nil
}
