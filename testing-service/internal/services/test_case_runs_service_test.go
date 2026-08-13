package services

import (
	"context"
	"encoding/csv"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
)

func exportedRows(t *testing.T, exported string) [][]string {
	t.Helper()
	reader := csv.NewReader(strings.NewReader(exported))
	reader.FieldsPerRecord = -1
	rows, err := reader.ReadAll()
	require.NoError(t, err)
	return rows
}

func TestExportToCsvReturnsNothingForAnEmptySelection(t *testing.T) {
	service := NewTestCaseRunsService(&fakeRunner{}, Repositories{TestCaseRuns: &fakeTestCaseRunsRepository{}})

	exported, err := service.ExportToCsv(context.Background(), nil)

	require.NoError(t, err)
	assert.Empty(t, exported)
}

func TestExportSkipsTheQueryWhenNoIdsWereGiven(t *testing.T) {
	repository := &fakeTestCaseRunsRepository{views: []dao.TestCaseRunView{{}}}
	service := NewTestCaseRunsService(&fakeRunner{}, Repositories{TestCaseRuns: repository})

	byIds, err := service.Export(context.Background(), &[]uuid.UUID{})
	require.NoError(t, err)
	byTestsRuns, err := service.ExportByTestsRunIds(context.Background(), nil)
	require.NoError(t, err)

	assert.Empty(t, byIds)
	assert.Empty(t, byTestsRuns)
}

func TestExportToCsvWritesOneRowPerRunWithoutErrors(t *testing.T) {
	runID := uuid.New()
	testsRunID := uuid.New()
	testCaseID := uuid.New()
	start := time.Date(2026, 8, 13, 10, 0, 0, 0, time.UTC)
	status := dao.RunStatusFinished
	repository := &fakeTestCaseRunsRepository{views: []dao.TestCaseRunView{{
		TestCaseRun: dao.TestCaseRun{
			ID:         runID,
			TestsRunID: &testsRunID,
			TestCaseID: &testCaseID,
			Start:      &start,
			Status:     &status,
		},
	}}}
	service := NewTestCaseRunsService(&fakeRunner{}, Repositories{TestCaseRuns: repository})

	exported, err := service.ExportToCsv(context.Background(), nil)

	require.NoError(t, err)
	rows := exportedRows(t, exported)
	require.Len(t, rows, 2)
	assert.Equal(t, testCaseRunCsvHeader, rows[0])
	assert.Equal(t, testsRunID.String(), rows[1][0])
	assert.Equal(t, runID.String(), rows[1][1], "the run id belongs in its own column")
	assert.Equal(t, testCaseID.String(), rows[1][3])
	assert.Equal(t, start.Format(time.RFC3339Nano), rows[1][6])
	assert.Empty(t, rows[1][7], "an unfinished run has no finish timestamp")
	assert.Equal(t, dao.RunStatusFinished, rows[1][8])
}

func TestExportToCsvWritesOneRowPerValidationError(t *testing.T) {
	runID := uuid.New()
	matcherID := uuid.New()
	repository := &fakeTestCaseRunsRepository{views: []dao.TestCaseRunView{{
		TestCaseRun: dao.TestCaseRun{ID: runID},
		Errors:      2,
	}}}
	errorsRepository := &fakeTestCaseRunErrorsRepository{byTestCaseRun: map[uuid.UUID][]dao.ValidationError{
		runID: {
			{MatcherID: &matcherID, Matcher: &dao.Matcher{Name: "status", Description: "is 200"}, Message: "got 500"},
			{Message: "no response"},
		},
	}}
	service := NewTestCaseRunsService(&fakeRunner{}, Repositories{
		TestCaseRuns:      repository,
		TestCaseRunErrors: errorsRepository,
	})

	exported, err := service.ExportToCsv(context.Background(), nil)

	require.NoError(t, err)
	rows := exportedRows(t, exported)
	require.Len(t, rows, 3)
	assert.Equal(t, []string{matcherID.String(), "status", "is 200", "got 500"}, rows[1][11:])
	// The second row must not inherit the first one's matcher columns.
	assert.Equal(t, []string{"", "", "", "no response"}, rows[2][11:])
}

func TestStartRunningAndFinishingStampTheExpectedStatuses(t *testing.T) {
	repository := &fakeTestCaseRunsRepository{}
	service := NewTestCaseRunsService(&fakeRunner{}, Repositories{TestCaseRuns: repository})
	id := uuid.New()

	require.NoError(t, service.Start(context.Background(), id, "session-1"))
	require.NoError(t, service.Finish(context.Background(), id))
	require.NoError(t, service.Skip(context.Background(), id))

	require.Len(t, repository.updated, 3)
	assert.Equal(t, dao.RunStatusRunning, *repository.updated[0].Status)
	assert.Equal(t, "session-1", *repository.updated[0].SessionID)
	assert.NotNil(t, repository.updated[0].Start)
	assert.Equal(t, dao.RunStatusFinished, *repository.updated[1].Status)
	assert.NotNil(t, repository.updated[1].Finish)
	assert.Equal(t, dao.RunStatusSkipped, *repository.updated[2].Status)
}
