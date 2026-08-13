package services

import (
	"context"
	"encoding/csv"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
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
	service := NewTestCaseRunsService(config.Config{}, &fakeRunner{}, Repositories{TestCaseRuns: &fakeTestCaseRunsRepository{}})

	exported, err := service.ExportToCsv(context.Background(), nil)

	require.NoError(t, err)
	assert.Empty(t, exported)
}

func TestExportSkipsTheQueryWhenNoIdsWereGiven(t *testing.T) {
	repository := &fakeTestCaseRunsRepository{views: []dao.TestCaseRunView{{}}}
	service := NewTestCaseRunsService(config.Config{}, &fakeRunner{}, Repositories{TestCaseRuns: repository})

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
	service := NewTestCaseRunsService(config.Config{}, &fakeRunner{}, Repositories{TestCaseRuns: repository})

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
	service := NewTestCaseRunsService(config.Config{}, &fakeRunner{}, Repositories{
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

func TestFinishingAndSkippingStampTheExpectedStatusesUnderTheOwnerToken(t *testing.T) {
	repository := &fakeTestCaseRunsRepository{}
	service := NewTestCaseRunsService(config.Config{}, &fakeRunner{}, Repositories{TestCaseRuns: repository})
	id := uuid.New()
	owner := uuid.New()

	require.NoError(t, service.Finish(context.Background(), id, owner))
	require.NoError(t, service.Skip(context.Background(), id, owner))

	require.Len(t, repository.updated, 2)
	assert.Equal(t, dao.RunStatusFinished, *repository.updated[0].Status)
	assert.NotNil(t, repository.updated[0].Finish)
	assert.Equal(t, dao.RunStatusSkipped, *repository.updated[1].Status)
	assert.Equal(t, []uuid.UUID{owner, owner}, repository.updateOwners, "both writes name the owner they claimed under")
}

func TestFinishingAndSkippingAreRefusedOnceAnotherWorkerOwnsTheRun(t *testing.T) {
	current := uuid.New()
	repository := &fakeTestCaseRunsRepository{leaseOwner: &current}
	service := NewTestCaseRunsService(config.Config{}, &fakeRunner{}, Repositories{TestCaseRuns: repository})
	id := uuid.New()
	swept := uuid.New()

	require.ErrorIs(t, service.Finish(context.Background(), id, swept), dao.ErrLeaseLost)
	require.ErrorIs(t, service.Skip(context.Background(), id, swept), dao.ErrLeaseLost)

	assert.Empty(t, repository.updated, "a worker whose lease was swept writes nothing")
}

func TestClaimNextLeasesTheRunToTheGivenOwner(t *testing.T) {
	queued := &dao.TestCaseRun{ID: uuid.New()}
	repository := &fakeTestCaseRunsRepository{claimable: queued}
	cfg := config.Config{LeaseDuration: 90 * time.Second}
	service := NewTestCaseRunsService(cfg, &fakeRunner{}, Repositories{TestCaseRuns: repository})
	owner := uuid.New()

	claimed, err := service.ClaimNext(context.Background(), owner, "session-1")

	require.NoError(t, err)
	require.NotNil(t, claimed)
	assert.Equal(t, dao.RunStatusRunning, *claimed.Status, "the claim is what starts the run")
	assert.Equal(t, owner, *claimed.LeaseOwner)
	assert.Equal(t, "session-1", *claimed.SessionID)
	require.Len(t, repository.claims, 1)
	assert.Equal(t, claimCall{owner: owner, sessionID: "session-1", leaseDuration: 90 * time.Second}, repository.claims[0])
}

func TestRenewLeaseExtendsTheClaimForTheConfiguredDuration(t *testing.T) {
	repository := &fakeTestCaseRunsRepository{}
	cfg := config.Config{LeaseDuration: 90 * time.Second}
	service := NewTestCaseRunsService(cfg, &fakeRunner{}, Repositories{TestCaseRuns: repository})
	id := uuid.New()
	owner := uuid.New()

	require.NoError(t, service.RenewLease(context.Background(), id, owner))

	assert.Equal(t, []renewCall{{id: id, owner: owner, leaseDuration: 90 * time.Second}}, repository.renewals)
}

func TestRenewLeaseIsRefusedOnceAnotherWorkerOwnsTheRun(t *testing.T) {
	current := uuid.New()
	repository := &fakeTestCaseRunsRepository{leaseOwner: &current}
	service := NewTestCaseRunsService(config.Config{}, &fakeRunner{}, Repositories{TestCaseRuns: repository})

	err := service.RenewLease(context.Background(), uuid.New(), uuid.New())

	require.ErrorIs(t, err, dao.ErrLeaseLost)
	assert.Empty(t, repository.renewals, "a swept worker may not extend a lease it no longer holds")
}

func TestReclaimExpiredReportsHowManyRunsReturnedToTheQueue(t *testing.T) {
	repository := &fakeTestCaseRunsRepository{reclaimable: 3}
	service := NewTestCaseRunsService(config.Config{}, &fakeRunner{}, Repositories{TestCaseRuns: repository})

	reclaimed, err := service.ReclaimExpired(context.Background())

	require.NoError(t, err)
	assert.Equal(t, 3, reclaimed)
	assert.Equal(t, 1, repository.reclaims)
}

func TestReclaimExpiredReportsAFailingSweep(t *testing.T) {
	repository := &fakeTestCaseRunsRepository{reclaimErr: errors.New("no connection")}
	service := NewTestCaseRunsService(config.Config{}, &fakeRunner{}, Repositories{TestCaseRuns: repository})

	reclaimed, err := service.ReclaimExpired(context.Background())

	require.Error(t, err)
	assert.Zero(t, reclaimed)
}

func TestClaimNextLeasesForTheDefaultDurationWhenNoneIsConfigured(t *testing.T) {
	repository := &fakeTestCaseRunsRepository{}
	service := NewTestCaseRunsService(config.Config{}, &fakeRunner{}, Repositories{TestCaseRuns: repository})

	claimed, err := service.ClaimNext(context.Background(), uuid.New(), "session-1")

	require.NoError(t, err)
	assert.Nil(t, claimed, "an empty queue yields no run")
	require.Len(t, repository.claims, 1)
	assert.Equal(t, config.DefaultLeaseDuration, repository.claims[0].leaseDuration)
}
