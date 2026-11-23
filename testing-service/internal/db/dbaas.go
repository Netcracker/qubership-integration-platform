package db

import (
	dbaasbase "github.com/netcracker/qubership-core-lib-go-dbaas-base-client/v3"
	pgdbaas "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4"
)

func NewDbaaS() (pgdbaas.PgClient, error) {
	pool := dbaasbase.NewDbaaSPool()
	return pgdbaas.NewClient(pool).ServiceDatabase().GetPgClient()
}
