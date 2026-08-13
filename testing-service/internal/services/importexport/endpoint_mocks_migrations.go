package importexport

import "github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"

// GetEndpointMocksDataMigrations returns the migrations for exported endpoint
// mocks, in version order. The exported shape has not changed yet, so the chain
// is empty.
func GetEndpointMocksDataMigrations() []model.ExportedDataMigrationFunction {
	return []model.ExportedDataMigrationFunction{}
}

// GetEndpointMocksActualDataVersion returns the version endpoint mocks are
// exported at.
func GetEndpointMocksActualDataVersion() int {
	return GetActualDataVersion(GetEndpointMocksDataMigrations())
}
