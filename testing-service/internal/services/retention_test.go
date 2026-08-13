package services

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
)

type retentionFixture struct {
	service   *testsRunsService
	testsRuns *fakeTestsRunsRepository
}

func newRetentionFixture(cfg config.Config, expired []uuid.UUID) *retentionFixture {
	testsRuns := &fakeTestsRunsRepository{expired: expired}
	service := testsRunsServiceWith(cfg, &fakeRunner{}, Repositories{TestsRuns: testsRuns}, nil)
	return &retentionFixture{service: service.(*testsRunsService), testsRuns: testsRuns}
}

// expiredRuns names count test runs that reached the retention age.
func expiredRuns(count int) []uuid.UUID {
	runs := make([]uuid.UUID, 0, count)
	for range count {
		runs = append(runs, uuid.New())
	}
	return runs
}

func TestRetentionKeepsEveryRunWhenNoAgeIsConfigured(t *testing.T) {
	fixture := newRetentionFixture(config.Config{RetentionInterval: time.Millisecond}, expiredRuns(3))

	// The context is never canceled on purpose: a disabled retention has to return
	// on its own, and a test that hangs here is the failure.
	fixture.service.RunRetention(context.Background())

	assert.Empty(t, fixture.testsRuns.sweeps(), "with no age configured nothing may be deleted")
}

func TestRetentionDeletesTheRunsThatReachedTheAge(t *testing.T) {
	expired := expiredRuns(2)
	fixture := newRetentionFixture(
		config.Config{RetentionAge: 72 * time.Hour, RetentionInterval: time.Millisecond},
		expired,
	)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		fixture.service.RunRetention(ctx)
	}()

	require.Eventually(t, func() bool {
		return len(fixture.testsRuns.deletedRuns()) == len(expired)
	}, 5*time.Second, time.Millisecond)

	cancel()
	select {
	case <-stopped:
	case <-time.After(5 * time.Second):
		t.Fatal("RunRetention did not return after the context was canceled")
	}

	assert.Equal(t, expired, fixture.testsRuns.deletedRuns())
	sweeps := fixture.testsRuns.sweeps()
	require.NotEmpty(t, sweeps)
	assert.Equal(t, 72*time.Hour, sweeps[0].age, "the configured age is what the statement compares against")
	assert.Equal(t, retentionBatchSize, sweeps[0].batchSize)
}

func TestRetentionWorksOffABacklogInBatches(t *testing.T) {
	expired := expiredRuns(retentionBatchSize + 3)
	fixture := newRetentionFixture(config.Config{RetentionAge: time.Hour}, expired)

	deleted, err := fixture.service.deleteExpired(context.Background())

	require.NoError(t, err)
	assert.Equal(t, len(expired), deleted)
	assert.Equal(t, expired, fixture.testsRuns.deletedRuns())
	assert.Len(t, fixture.testsRuns.sweeps(), 2, "a batch that comes back short is what ends the pass")
}

func TestRetentionStopsAfterAFailedBatch(t *testing.T) {
	fixture := newRetentionFixture(config.Config{RetentionAge: time.Hour}, expiredRuns(2))
	fixture.testsRuns.deleteExpiredErr = errors.New("no connection")

	deleted, err := fixture.service.deleteExpired(context.Background())

	require.Error(t, err)
	assert.Zero(t, deleted)
	assert.Len(t, fixture.testsRuns.sweeps(), 1, "a failing batch waits for the next sweep rather than spinning")
}

func TestRetentionStopsWorkingOffTheBacklogOnCancellation(t *testing.T) {
	fixture := newRetentionFixture(config.Config{RetentionAge: time.Hour}, expiredRuns(retentionBatchSize+3))
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	deleted, err := fixture.service.deleteExpired(ctx)

	require.NoError(t, err)
	assert.Zero(t, deleted)
	assert.Empty(t, fixture.testsRuns.sweeps())
}
