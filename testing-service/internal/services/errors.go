package services

import (
	"errors"
	"fmt"
)

// ErrInvalidRequest marks input the caller sent wrong. It is what tells such a
// failure from a database one, so the HTTP layer answers 400 with the
// explanation instead of 500 with none.
var ErrInvalidRequest = errors.New("invalid request")

// invalidRequestError carries the explanation on its own: the sentinel is
// matched with errors.Is, never printed, so the message the caller reads is not
// prefixed with it.
type invalidRequestError struct {
	message string
}

func (e *invalidRequestError) Error() string { return e.message }

func (e *invalidRequestError) Unwrap() error { return ErrInvalidRequest }

func invalidRequest(format string, args ...any) error {
	return &invalidRequestError{message: fmt.Sprintf(format, args...)}
}

// ErrNotFound marks a request that named an entity this service does not hold.
// A stale id is the caller's own mistake, so the HTTP layer answers 404 with the
// same message its sibling read endpoint answers, not 500 about this service.
var ErrNotFound = errors.New("not found")

// notFoundError carries the explanation on its own, for the reason
// invalidRequestError does.
type notFoundError struct {
	message string
}

func (e *notFoundError) Error() string { return e.message }

func (e *notFoundError) Unwrap() error { return ErrNotFound }

func notFound(format string, args ...any) error {
	return &notFoundError{message: fmt.Sprintf(format, args...)}
}
