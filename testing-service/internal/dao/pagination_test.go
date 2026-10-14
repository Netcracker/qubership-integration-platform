package dao

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/uptrace/bun"
	"github.com/uptrace/bun/dialect/pgdialect"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// renderPaginated returns the SQL AddPagination produced for one window.
func renderPaginated(t *testing.T, options model.PaginationOptions, maxLimit int) string {
	t.Helper()
	db := bun.NewDB(nil, pgdialect.New())
	query := AddPagination(db.NewSelect().Table("t"), options, maxLimit)
	sql, err := query.AppendQuery(db.Formatter(), nil)
	require.NoError(t, err)
	return string(sql)
}

func TestAddPaginationAppliesTheRequestedWindow(t *testing.T) {
	assert.Equal(t, `SELECT * FROM "t" LIMIT 20 OFFSET 40`,
		renderPaginated(t, model.PaginationOptions{Offset: 40, Limit: 20}, 100))
}

func TestAddPaginationOmitsTheOffsetOfTheFirstPage(t *testing.T) {
	assert.Equal(t, `SELECT * FROM "t" LIMIT 20`,
		renderPaginated(t, model.PaginationOptions{Limit: 20}, 100))
}

func TestAddPaginationClampsTheLimitToThePageSizeTheServiceServes(t *testing.T) {
	// A request for more than the service serves is answered with the page size
	// rather than refused, and a request for nothing gets a page rather than the
	// whole table.
	assert.Equal(t, `SELECT * FROM "t" LIMIT 100`,
		renderPaginated(t, model.PaginationOptions{Limit: 5000}, 100))
	assert.Equal(t, `SELECT * FROM "t" LIMIT 100`,
		renderPaginated(t, model.PaginationOptions{}, 100))
	assert.Equal(t, `SELECT * FROM "t" LIMIT 100`,
		renderPaginated(t, model.PaginationOptions{Limit: -1}, 100))
}

func TestAddPaginationAlwaysLimits(t *testing.T) {
	// Every listing is bounded: an unlimited select over a retained history is
	// what the page size exists to prevent.
	assert.Contains(t, renderPaginated(t, model.PaginationOptions{}, 1), "LIMIT 1")
}

func TestEffectiveLimitClampsTheRequestedPageSize(t *testing.T) {
	cases := []struct {
		name      string
		requested int
		maxLimit  int
		want      int
	}{
		{"a request within the cap is honored", 5, 20, 5},
		{"a request at the cap is honored", 20, 20, 20},
		{"a request above the cap falls back to it", 500, 20, 20},
		{"an unset limit falls back to the cap", 0, 20, 20},
		{"a negative limit falls back to the cap", -1, 20, 20},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			assert.Equal(t, tc.want, effectiveLimit(tc.requested, tc.maxLimit))
		})
	}
}
