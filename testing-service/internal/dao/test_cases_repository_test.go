package dao

import (
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// A test case is updated from the request body, and the audit hook refreshes
// only the updated_* pair on an update. Writing the whole row would therefore
// let a body that omits createdAt null the stored value.
func TestTestCasesUpdateKeepsTheCreationAudit(t *testing.T) {
	ctx, recorded := recordingContext(t)
	repository := NewTestCasesRepository(discardLogger(), 100)

	require.NoError(t, repository.Update(ctx, &TestCase{ID: uuid.New(), Name: "orders"}))

	statement := recorded.only(t)
	assert.Contains(t, statement, `UPDATE "test_cases"`)
	assert.Contains(t, statement, `"name" = 'orders'`)
	assert.Contains(t, statement, `"updated_at" =`, "the audit hook still stamps the update")
	assert.NotContains(t, statement, `"created_at" =`)
	assert.NotContains(t, statement, `"created_by" =`)
}
