package dao

import (
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// An endpoint mock is updated the way a test case is, and a null created_at
// would also reorder the candidates the Call path picks from: it sorts first.
func TestEndpointMocksUpdateKeepsTheCreationAudit(t *testing.T) {
	ctx, recorded := recordingContext(t)
	repository := NewEndpointMocksRepository(discardLogger(), 100)

	require.NoError(t, repository.Update(ctx, &EndpointMock{ID: uuid.New(), Name: "orders"}))

	statement := recorded.only(t)
	assert.Contains(t, statement, `UPDATE "endpoint_mocks"`)
	assert.Contains(t, statement, `"updated_at" =`)
	assert.NotContains(t, statement, `"created_at" =`)
	assert.NotContains(t, statement, `"created_by" =`)
}
