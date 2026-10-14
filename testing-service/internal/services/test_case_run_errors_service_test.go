package services

import (
	"context"
	"encoding/csv"
	"errors"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
)

func testCaseRunErrorsServiceOver(
	repository *fakeTestCaseRunErrorsRepository,
) (TestCaseRunErrorsService, *fakeRunner) {
	runner := &fakeRunner{}
	return NewTestCaseRunErrorsService(runner, dao.Repositories{TestCaseRunErrors: repository}), runner
}

func csvRows(t *testing.T, exported string) [][]string {
	t.Helper()
	rows, err := csv.NewReader(strings.NewReader(exported)).ReadAll()
	require.NoError(t, err)
	return rows
}

func TestAddErrorFencesTheWriteOnTheClaimedOwner(t *testing.T) {
	owner := uuid.New()
	repository := &fakeTestCaseRunErrorsRepository{leaseOwner: &owner}
	service, runner := testCaseRunErrorsServiceOver(repository)
	testCaseRunID := uuid.New()
	matcher := &dao.Matcher{ID: uuid.New(), Name: "status"}

	stored, err := service.AddError(context.Background(), testCaseRunID, owner, matcher, "got 500")

	require.NoError(t, err)
	assert.Equal(t, matcher, stored.Matcher)
	assert.Equal(t, 1, runner.txCalls)
	require.Len(t, repository.inserted, 1)
	assert.Equal(t, testCaseRunID, *repository.inserted[0].TestCaseRunID)
	assert.Equal(t, matcher.ID, *repository.inserted[0].MatcherID)
	assert.Equal(t, []uuid.UUID{owner}, repository.insertOwners)
}

func TestAddErrorRefusesAWriteFromAWorkerThatLostTheLease(t *testing.T) {
	owner := uuid.New()
	repository := &fakeTestCaseRunErrorsRepository{leaseOwner: &owner}
	service, _ := testCaseRunErrorsServiceOver(repository)

	stored, err := service.AddError(context.Background(), uuid.New(), uuid.New(), nil, "too late")

	require.ErrorIs(t, err, dao.ErrLeaseLost)
	assert.Nil(t, stored)
	assert.Empty(t, repository.inserted)
}

func TestAddErrorWithoutAMatcherLeavesTheRuleUnnamed(t *testing.T) {
	repository := &fakeTestCaseRunErrorsRepository{}
	service, _ := testCaseRunErrorsServiceOver(repository)

	stored, err := service.AddError(context.Background(), uuid.New(), uuid.New(), nil, "the chain is gone")

	require.NoError(t, err)
	assert.Nil(t, stored.Matcher)
	require.Len(t, repository.inserted, 1)
	assert.Nil(t, repository.inserted[0].MatcherID)
}

func TestBulkExportWritesTheRuleAndTheMessage(t *testing.T) {
	first := uuid.New()
	second := uuid.New()
	matcherID := uuid.New()
	repository := &fakeTestCaseRunErrorsRepository{byTestCaseRun: map[uuid.UUID][]dao.ValidationError{
		first:  {{MatcherID: &matcherID, Matcher: &dao.Matcher{Name: "status is 200"}, Message: "got 500"}},
		second: {{Message: "no matcher at all"}},
	}}
	service, _ := testCaseRunErrorsServiceOver(repository)

	exported, err := service.BulkExport(context.Background(), &[]uuid.UUID{first, second})

	require.NoError(t, err)
	rows := csvRows(t, exported)
	require.Len(t, rows, 3)
	assert.Equal(t, []string{"Rule", "Message"}, rows[0])
	assert.Equal(t, []string{"status is 200", "got 500"}, rows[1])
	assert.Equal(t, []string{"N/A", "no matcher at all"}, rows[2], "an error with no rule at all is named N/A")
}

// The name comes off the matcher when it was loaded and off the id otherwise, so
// an export without the relations still says which rule failed.
func TestBulkExportFallsBackToTheMatcherIdWhenTheRuleWasNotLoaded(t *testing.T) {
	testCaseRunID := uuid.New()
	matcherID := uuid.New()
	repository := &fakeTestCaseRunErrorsRepository{byTestCaseRun: map[uuid.UUID][]dao.ValidationError{
		testCaseRunID: {{MatcherID: &matcherID, Message: "got 500"}},
	}}
	service, _ := testCaseRunErrorsServiceOver(repository)

	exported, err := service.BulkExport(context.Background(), &[]uuid.UUID{testCaseRunID})

	require.NoError(t, err)
	rows := csvRows(t, exported)
	require.Len(t, rows, 2)
	assert.Equal(t, []string{matcherID.String(), "got 500"}, rows[1])
}

func TestBulkExportReturnsNothingForASelectionWithNoErrors(t *testing.T) {
	service, _ := testCaseRunErrorsServiceOver(&fakeTestCaseRunErrorsRepository{})

	exported, err := service.BulkExport(context.Background(), &[]uuid.UUID{uuid.New()})

	require.NoError(t, err)
	assert.Empty(t, exported)
}

// A value that opens with =, +, - or @ is a formula to a spreadsheet, and both
// the rule name and the message are text a user wrote.
func TestBulkExportNeutralisesAValueThatWouldRunAsAFormula(t *testing.T) {
	testCaseRunID := uuid.New()
	repository := &fakeTestCaseRunErrorsRepository{byTestCaseRun: map[uuid.UUID][]dao.ValidationError{
		testCaseRunID: {{
			Matcher: &dao.Matcher{Name: `=cmd|'/c calc'!A1`},
			Message: "@SUM(1,2)",
		}},
	}}
	service, _ := testCaseRunErrorsServiceOver(repository)

	exported, err := service.BulkExport(context.Background(), &[]uuid.UUID{testCaseRunID})

	require.NoError(t, err)
	rows := csvRows(t, exported)
	require.Len(t, rows, 2)
	assert.Equal(t, `'=cmd|'/c calc'!A1`, rows[1][0])
	assert.Equal(t, "'@SUM(1,2)", rows[1][1])
}

func TestCsvFieldNeutralisesEveryFormulaLeader(t *testing.T) {
	for _, value := range []string{"=1+1", "+1", "-1", "@A1", "\tinjected", "\rinjected"} {
		assert.Equal(t, "'"+value, csvField(value), "a leading %q starts a formula", value[:1])
	}
}

func TestCsvFieldLeavesOrdinaryValuesAlone(t *testing.T) {
	for _, value := range []string{"", "order flow", "200", "a=b", "the-name"} {
		assert.Equal(t, value, csvField(value))
	}
}

func TestBulkExportReportsAFailingQuery(t *testing.T) {
	failure := errors.New("no connection")
	service, _ := testCaseRunErrorsServiceOver(&fakeTestCaseRunErrorsRepository{findErr: failure})

	exported, err := service.BulkExport(context.Background(), &[]uuid.UUID{uuid.New()})

	require.ErrorIs(t, err, failure)
	assert.Empty(t, exported)
}
