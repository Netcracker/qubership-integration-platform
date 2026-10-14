//go:build integration

package dao_test

import (
	"context"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// findRuns lists the test runs the given id selection covers. A nil list is no
// id restriction at all; a non-nil one is the selection itself.
func (q *queue) findRuns(t *testing.T, ids *[]uuid.UUID) []dao.TestsRunView {
	t.Helper()
	found, err := dao.Run(context.Background(), q.dao, func(ctx context.Context) (*[]dao.TestsRunView, error) {
		return q.dao.Repositories.TestsRuns.FindAll(
			ctx,
			&model.SelectionSpecification{Ids: ids},
			model.SortOptions{Order: model.OrderAscending},
			nil,
		)
	})
	require.NoError(t, err)
	return *found
}

// An empty selection selects nothing, and PostgreSQL has to agree: the predicate
// built for an empty id list has to return no rows. Left out of the query, as it
// once was, the same request answers with every row of the table.
func TestAnEmptyIdListSelectsNoRows(t *testing.T) {
	q := newQueue(t)
	first := q.seedRun(t, 1)
	q.seedRun(t, 1)

	assert.Len(t, q.findRuns(t, nil), 2)
	assert.Len(t, q.findRuns(t, &[]uuid.UUID{first}), 1)
	assert.Empty(t, q.findRuns(t, &[]uuid.UUID{}))
}
