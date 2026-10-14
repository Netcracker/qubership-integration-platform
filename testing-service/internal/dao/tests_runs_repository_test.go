package dao

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
)

// The guards below are the whole safety of retention, and they live in the
// statement rather than in Go, so this is where dropping one shows up without a
// database. The integration suite exercises the same statement for real.
func TestDeleteExpiredLeavesTheRunsThatStillHaveWorkAlone(t *testing.T) {
	statement := strings.Join(strings.Fields(deleteExpiredTestsRunsQuery), " ")

	assert.Contains(t, statement, "not exists ( select 1 from test_case_runs c",
		"a run with a case waiting or in flight may not be deleted")
	assert.Contains(t, statement, "c.status in (?, ?)")
	assert.Contains(t, statement, "r.created_at < now() - make_interval(secs => ?)",
		"age comes from the run, and from the database clock")
	assert.Contains(t, statement, "limit ?", "the batch is what keeps the transaction short")
}
