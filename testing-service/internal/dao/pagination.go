package dao

import (
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// AddPagination applies the requested window, clamped to maxLimit.
func AddPagination(query *bun.SelectQuery, options model.PaginationOptions, maxLimit int) *bun.SelectQuery {
	q := query
	if options.Offset > 0 {
		q = q.Offset(options.Offset)
	}
	return q.Limit(effectiveLimit(options.Limit, maxLimit))
}

// effectiveLimit falls back to maxLimit for a request that asks for nothing or
// for more than the page size the service serves.
func effectiveLimit(requested, maxLimit int) int {
	if requested < 1 || requested > maxLimit {
		return maxLimit
	}
	return requested
}
