//go:build integration

package dao

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/uptrace/bun"
)

// What the claim tests reach for: the two statements of Claim, one at a time.
// Driving them apart is the only way to put a worker where the interleaving the
// second statement guards against leaves it.

// ClaimTestsRunStatement returns the first statement of Claim as PostgreSQL
// receives it, arguments and all, so a test can run it through a cursor and
// decide when its rows are locked.
func ClaimTestsRunStatement(db *bun.DB) (string, error) {
	query, args := buildClaimTestsRunQuery(nil)
	statement, err := db.NewRaw(query, args...).AppendQuery(db.Formatter(), nil)
	if err != nil {
		return "", err
	}
	return string(statement), nil
}

// ClaimTestCaseRun runs the second statement of Claim against a test run the
// caller locked itself.
func ClaimTestCaseRun(
	ctx context.Context,
	db bun.IDB,
	testsRunID uuid.UUID,
	owner uuid.UUID,
	sessionID string,
	leaseDuration time.Duration,
) (*TestCaseRun, error) {
	repository := &testCaseRunsRepository{}
	return repository.claimTestCaseRun(ctx, db, testsRunID, owner, sessionID, leaseDuration)
}
