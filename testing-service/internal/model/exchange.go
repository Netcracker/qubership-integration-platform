// Package model holds the value types shared by the repositories, the matching
// engine and the HTTP layer.
package model

// Exchange is the request or response a matcher is evaluated against.
type Exchange struct {
	Headers map[string][]string
	Body    []byte
	Status  int
}
