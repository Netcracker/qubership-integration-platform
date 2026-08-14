// Package qip talks to the platform services the testing service depends on:
// the runtime catalog, which describes the chain elements a test case activates.
package qip

// ChainElement is one element of an integration chain, cut down to what the
// trigger resolver reads. The catalog answers with a great deal more — the
// audit fields, the swimlane, the whole subtree of children — and decoding any
// of it would only make the reply larger to walk.
type ChainElement struct {
	Type string `json:"type"`
	// Properties carries the element-type-specific settings, such as the path an
	// HTTP trigger listens on.
	Properties map[string]any `json:"properties"`
}
