// Package qip talks to the platform services the testing service depends on:
// the runtime catalog, which describes chain elements, and the engine, which
// serves the chain triggers a test case activates.
package qip

// User identifies who created or last changed a catalog entity.
type User struct {
	ID   string `json:"id"`
	Name string `json:"username"`
}

// BaseModel holds the fields every catalog entity carries.
type BaseModel struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	Description  string `json:"description"`
	CreatedWhen  int64  `json:"createdWhen"`
	CreatedBy    User   `json:"createdBy"`
	ModifiedWhen int64  `json:"modifiedWhen"`
	ModifiedBy   User   `json:"modifiedBy"`
}

// ChainElement is one element of an integration chain. Properties carries the
// element-type-specific settings, such as the path an HTTP trigger listens on.
type ChainElement struct {
	BaseModel

	ChainID               string         `json:"chainId"`
	Type                  string         `json:"type"`
	ParentElementID       string         `json:"parentElementId"`
	OriginalID            string         `json:"originalId"`
	Properties            map[string]any `json:"properties"`
	Children              []ChainElement `json:"children"`
	SwimlaneID            string         `json:"swimlaneId"`
	MandatoryChecksPassed bool           `json:"mandatoryChecksPassed"`
}
