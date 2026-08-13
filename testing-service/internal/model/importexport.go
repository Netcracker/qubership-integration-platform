package model

import (
	"encoding/json"

	"github.com/google/uuid"
)

// Outcomes reported for a single file of an imported archive.
const (
	ImportResultCreated = "created"
	ImportResultUpdated = "updated"
	ImportResultError   = "error"
)

// Entity kinds an archive may carry.
const (
	EntityTypeTestCase     = "TestCase"
	EntityTypeEndpointMock = "EndpointMock"
)

type ImportResult struct {
	Archive    string     `json:"archive"`
	FileName   string     `json:"fileName"`
	EntityID   *uuid.UUID `json:"entityId"`
	EntityName *string    `json:"entityName"`
	Result     string     `json:"result"`
	Message    string     `json:"message"`
} // @name ImportResult

type ExportedEntity struct {
	Version int             `json:"version"`
	Type    string          `json:"type"`
	ID      uuid.UUID       `json:"id"`
	Name    string          `json:"name"`
	Data    json.RawMessage `json:"data"`
}

// ExportedDataMigrationFunction upgrades one exported entity to the next version.
type ExportedDataMigrationFunction = func(data *json.RawMessage) (*json.RawMessage, error)
