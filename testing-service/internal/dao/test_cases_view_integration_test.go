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

// seedCase stores a test case carrying the given number of rules, the first
// `enabled` of them enabled, and returns its id.
func (q *queue) seedCase(t *testing.T, rules, enabled int) uuid.UUID {
	t.Helper()
	testCaseID := uuid.New()
	q.exec(t, "insert into test_cases (id, name) values (?, ?)", testCaseID, "case")
	for rule := range rules {
		q.exec(t, "insert into matchers (id, owner_id, name, enabled) values (?, ?, ?, ?)",
			uuid.New(), testCaseID, "rule", rule < enabled)
	}
	return testCaseID
}

// findCase reads one test case back through the view the list screens read.
func (q *queue) findCase(t *testing.T, id uuid.UUID) dao.TestCaseView {
	t.Helper()
	found, err := dao.Run(context.Background(), q.dao, func(ctx context.Context) (*[]dao.TestCaseView, error) {
		return q.dao.Repositories.TestCases.FindAll(
			ctx,
			&model.SelectionSpecification{Ids: &[]uuid.UUID{id}},
			model.SortOptions{Order: model.OrderAscending},
			nil,
			false,
		)
	})
	require.NoError(t, err)
	require.Len(t, *found, 1)
	return (*found)[0]
}

// The rule counts are what the test case list renders, and joining the matchers
// once per count multiplied them: every row of the first join met every row of
// the second, so both counts answered with the product. Three rules, all
// enabled, reported nine of each.
func TestTheRuleCountsCountEachRuleOnce(t *testing.T) {
	q := newQueue(t)

	for _, testCase := range []struct {
		name            string
		rules, enabled  int
		wantAll, wantOn int
	}{
		{name: "all enabled", rules: 3, enabled: 3, wantAll: 3, wantOn: 3},
		{name: "some enabled", rules: 3, enabled: 2, wantAll: 3, wantOn: 2},
		{name: "one of five enabled", rules: 5, enabled: 1, wantAll: 5, wantOn: 1},
		{name: "none enabled", rules: 3, enabled: 0, wantAll: 3, wantOn: 0},
		{name: "no rules at all", rules: 0, enabled: 0, wantAll: 0, wantOn: 0},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			view := q.findCase(t, q.seedCase(t, testCase.rules, testCase.enabled))

			assert.Equal(t, testCase.wantAll, view.ValidationRuleCount)
			assert.Equal(t, testCase.wantOn, view.EnabledRuleCount)
		})
	}
}

// A case with no rules is the one shape the old view also got right, and it is
// the shape a left join reports as one null row rather than none.
func TestACaseWithoutRulesCountsNothingRatherThanItsOwnRow(t *testing.T) {
	q := newQueue(t)

	view := q.findCase(t, q.seedCase(t, 0, 0))

	assert.Zero(t, view.ValidationRuleCount)
	assert.Zero(t, view.EnabledRuleCount)
}

// The counts belong to the case that owns the rules: a second case must not pick
// up rows through the join.
func TestTheRuleCountsStayWithTheirOwnCase(t *testing.T) {
	q := newQueue(t)
	loaded := q.seedCase(t, 4, 2)
	bare := q.seedCase(t, 0, 0)

	assert.Equal(t, 4, q.findCase(t, loaded).ValidationRuleCount)
	assert.Equal(t, 2, q.findCase(t, loaded).EnabledRuleCount)
	assert.Zero(t, q.findCase(t, bare).ValidationRuleCount)
}
