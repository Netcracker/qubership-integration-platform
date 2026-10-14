package dao

import (
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestUpdateOwnedFencesTheWriteAndOmitsWhatItDoesNotReport(t *testing.T) {
	ctx, recorded := recordingContext(t)
	repository := NewTestCaseRunsRepository(discardLogger(), 100)
	owner := uuid.New()
	status := RunStatusFinished

	require.NoError(t, repository.UpdateOwned(ctx, &TestCaseRun{ID: uuid.New(), Status: &status}, owner))

	statement := recorded.only(t)
	assert.Contains(t, statement, `lease_owner = '`+owner.String()+`'`, "the write carries the fencing token")
	assert.Contains(t, statement, `SET "status" = 'finished' WHERE`)
	// The worker reports one part of the row at a time; the columns it left alone
	// belong to the claim and must survive.
	assert.NotContains(t, statement, `"session_id" =`)
	assert.NotContains(t, statement, `"ordinal" =`)
	assert.NotContains(t, statement, `"lease_owner" =`, "the fence is a predicate, never a column the update writes")
}
