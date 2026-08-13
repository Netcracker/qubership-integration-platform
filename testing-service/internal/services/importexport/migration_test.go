package importexport

import (
	"encoding/json"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// renaming produces a migration that replaces the payload with the given text.
func renaming(text string) model.ExportedDataMigrationFunction {
	return func(*json.RawMessage) (*json.RawMessage, error) {
		data := json.RawMessage(`"` + text + `"`)
		return &data, nil
	}
}

func TestGetActualDataVersionCountsFromOne(t *testing.T) {
	assert.Equal(t, 1, GetActualDataVersion(nil))
	assert.Equal(t, 3, GetActualDataVersion([]model.ExportedDataMigrationFunction{renaming("a"), renaming("b")}))
}

func TestMigrateEntityDataLeavesCurrentDataAlone(t *testing.T) {
	data := json.RawMessage(`{"name":"first"}`)

	migrated, err := MigrateEntityData(&data, 1, nil)

	require.NoError(t, err)
	assert.JSONEq(t, `{"name":"first"}`, string(*migrated))
}

func TestMigrateEntityDataRunsOnlyTheMigrationsAfterTheDataVersion(t *testing.T) {
	data := json.RawMessage(`{}`)
	migrations := []model.ExportedDataMigrationFunction{renaming("v2"), renaming("v3")}

	migrated, err := MigrateEntityData(&data, 2, migrations)

	require.NoError(t, err)
	assert.JSONEq(t, `"v3"`, string(*migrated))
}

func TestMigrateEntityDataRejectsDataFromANewerBuild(t *testing.T) {
	data := json.RawMessage(`{}`)

	migrated, err := MigrateEntityData(&data, 4, []model.ExportedDataMigrationFunction{renaming("v2")})

	require.Error(t, err)
	assert.Nil(t, migrated)
	assert.ErrorContains(t, err, "higher than actual version")
}

// A version below one used to index the migration slice from -1 and panic.
func TestMigrateEntityDataRejectsAVersionBelowOne(t *testing.T) {
	data := json.RawMessage(`{}`)

	for _, version := range []int{0, -1} {
		migrated, err := MigrateEntityData(&data, version, nil)

		require.Error(t, err)
		assert.Nil(t, migrated)
	}
}

func TestMigrateEntityDataReportsAFailingMigration(t *testing.T) {
	failure := errors.New("unknown field")
	data := json.RawMessage(`{}`)
	migrations := []model.ExportedDataMigrationFunction{
		func(*json.RawMessage) (*json.RawMessage, error) { return nil, failure },
	}

	migrated, err := MigrateEntityData(&data, 1, migrations)

	require.ErrorIs(t, err, failure)
	assert.Nil(t, migrated)
}

func TestTestCasesAndEndpointMocksStartAtVersionOne(t *testing.T) {
	assert.Equal(t, 1, GetTestCasesActualDataVersion())
	assert.Equal(t, 1, GetEndpointMocksActualDataVersion())
}
