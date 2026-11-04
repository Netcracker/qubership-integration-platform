// Package db provides the database clients used by the standalone binary.
package db

import (
	dbaasbase "github.com/netcracker/qubership-core-lib-go-dbaas-base-client/v3"
	pgdbaas "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4"
)

// NewDbaaS creates the service-scoped PostgreSQL client backed by DBaaS.
//
// The returned client resolves a fresh bun handle on every GetBunDb call. That
// lets the DBaaS library refresh rotated credentials without the testing
// service caching a stale handle of its own.
func NewDbaaS() (pgdbaas.PgClient, error) {
	pool := dbaasbase.NewDbaaSPool()
	return pgdbaas.NewClient(pool).ServiceDatabase().GetPgClient()
}
