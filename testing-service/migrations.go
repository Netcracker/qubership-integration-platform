package testingservice

import (
	"embed"
	"fmt"

	"github.com/uptrace/bun/migrate"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrations returns the schema migrations of the testing service, for a host
// that applies them itself. Each call builds a fresh registry, so calling it
// twice does not register every migration twice.
func Migrations() (*migrate.Migrations, error) {
	migrations := migrate.NewMigrations()
	if err := migrations.Discover(migrationFiles); err != nil {
		return nil, fmt.Errorf("discover testing service migrations: %w", err)
	}
	return migrations, nil
}
