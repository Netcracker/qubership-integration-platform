// Package importexport reads and upgrades the entities carried by an exported
// archive.
package importexport

import (
	"encoding/json"
	"fmt"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// MigrateEntityData upgrades exported data from the version it was written at to
// the version this build understands, by running the migrations in between.
func MigrateEntityData(
	data *json.RawMessage,
	version int,
	migrations []model.ExportedDataMigrationFunction,
) (*json.RawMessage, error) {
	actualVersion := GetActualDataVersion(migrations)
	if version < 1 {
		return nil, fmt.Errorf("data version to import (%v) is below the first version (1)", version)
	}
	if version > actualVersion {
		return nil, fmt.Errorf("data version to import (%v) is higher than actual version (%v)", version, actualVersion)
	}
	result := data
	var err error
	for _, migration := range migrations[version-1:] {
		result, err = migration(result)
		if err != nil {
			return nil, err
		}
	}
	return result, nil
}

// GetActualDataVersion returns the version the given migration chain produces.
func GetActualDataVersion(migrations []model.ExportedDataMigrationFunction) int {
	return len(migrations) + 1
}
