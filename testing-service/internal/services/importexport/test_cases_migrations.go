package importexport

import "github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"

// GetTestCasesDataMigrations returns the migrations for exported test cases, in
// version order. The exported shape has not changed yet, so the chain is empty.
func GetTestCasesDataMigrations() []model.ExportedDataMigrationFunction {
	return []model.ExportedDataMigrationFunction{}
}

// GetTestCasesActualDataVersion returns the version test cases are exported at.
func GetTestCasesActualDataVersion() int {
	return GetActualDataVersion(GetTestCasesDataMigrations())
}
