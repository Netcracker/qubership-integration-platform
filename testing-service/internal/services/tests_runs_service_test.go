package services

import (
	"context"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func testsRunsServiceOver(repositories Repositories) (TestsRunsService, *fakeRunner) {
	runner := &fakeRunner{}
	testCaseRunsService := NewTestCaseRunsService(config.Config{}, runner, repositories)
	return NewTestsRunsService(runner, repositories, testCaseRunsService), runner
}

func TestStartNewRejectsAnEmptyTestCaseList(t *testing.T) {
	service, runner := testsRunsServiceOver(Repositories{})

	for _, ids := range []*[]uuid.UUID{nil, {}} {
		id, err := service.StartNew(context.Background(), ids)

		require.ErrorIs(t, err, ErrEmptyTestCaseList)
		assert.Nil(t, id)
	}
	assert.Zero(t, runner.txCalls, "no transaction should be opened for an empty list")
}

func TestStartNewRejectsATestCaseThatDoesNotExist(t *testing.T) {
	known := uuid.New()
	unknown := uuid.New()
	service, _ := testsRunsServiceOver(Repositories{
		TestCases:    &fakeTestCasesRepository{existing: map[uuid.UUID]bool{known: true}},
		TestsRuns:    &fakeTestsRunsRepository{},
		TestCaseRuns: &fakeTestCaseRunsRepository{},
	})

	id, err := service.StartNew(context.Background(), &[]uuid.UUID{known, unknown})

	require.Error(t, err)
	assert.ErrorContains(t, err, unknown.String())
	assert.Nil(t, id)
}

func TestStartNewQueuesOneCaseRunPerTestCase(t *testing.T) {
	first := uuid.New()
	second := uuid.New()
	testCaseRuns := &fakeTestCaseRunsRepository{}
	testsRuns := &fakeTestsRunsRepository{}
	service, _ := testsRunsServiceOver(Repositories{
		TestCases:    &fakeTestCasesRepository{existing: map[uuid.UUID]bool{first: true, second: true}},
		TestsRuns:    testsRuns,
		TestCaseRuns: testCaseRuns,
	})

	id, err := service.StartNew(context.Background(), &[]uuid.UUID{first, second})

	require.NoError(t, err)
	require.NotNil(t, id)
	require.Len(t, testsRuns.inserted, 1)
	assert.Equal(t, *id, testsRuns.inserted[0].ID)
	require.Len(t, testCaseRuns.inserted, 2)
	assert.Equal(t, first, *testCaseRuns.inserted[0].TestCaseID)
	assert.Equal(t, second, *testCaseRuns.inserted[1].TestCaseID)
	assert.Equal(t, *id, *testCaseRuns.inserted[0].TestsRunID)
}

func TestStartNewNumbersTheCaseRunsInTheOrderTheyWereSelected(t *testing.T) {
	first := uuid.New()
	second := uuid.New()
	third := uuid.New()
	testCaseRuns := &fakeTestCaseRunsRepository{}
	service, _ := testsRunsServiceOver(Repositories{
		TestCases: &fakeTestCasesRepository{
			existing: map[uuid.UUID]bool{first: true, second: true, third: true},
		},
		TestsRuns:    &fakeTestsRunsRepository{},
		TestCaseRuns: testCaseRuns,
	})

	_, err := service.StartNew(context.Background(), &[]uuid.UUID{first, second, third})

	require.NoError(t, err)
	require.Len(t, testCaseRuns.inserted, 3)
	ordinals := make([]int, 0, len(testCaseRuns.inserted))
	for _, testCaseRun := range testCaseRuns.inserted {
		require.NotNil(t, testCaseRun.Ordinal, "the claim orders by ordinal, so it may not be left unset")
		ordinals = append(ordinals, *testCaseRun.Ordinal)
	}
	assert.Equal(t, []int{1, 2, 3}, ordinals)
}

func TestStartNewFromEntitiesRejectsAnUnknownEntityType(t *testing.T) {
	service, _ := testsRunsServiceOver(Repositories{})

	id, err := service.StartNewFromEntitiesWithType(context.Background(), &[]uuid.UUID{uuid.New()}, "chains")

	require.Error(t, err)
	assert.ErrorContains(t, err, "chains")
	assert.Nil(t, id)
}

func TestStartNewFromTestsRunsCollectsTheirTestCasesWithoutDuplicates(t *testing.T) {
	shared := uuid.New()
	other := uuid.New()
	testCaseRuns := &fakeTestCaseRunsRepository{views: []dao.TestCaseRunView{
		{TestCaseRun: dao.TestCaseRun{TestCaseID: &shared}},
		{TestCaseRun: dao.TestCaseRun{TestCaseID: &other}},
		{TestCaseRun: dao.TestCaseRun{TestCaseID: &shared}},
		{TestCaseRun: dao.TestCaseRun{}},
	}}
	testsRuns := &fakeTestsRunsRepository{}
	service, _ := testsRunsServiceOver(Repositories{
		TestCases:    &fakeTestCasesRepository{existing: map[uuid.UUID]bool{shared: true, other: true}},
		TestsRuns:    testsRuns,
		TestCaseRuns: testCaseRuns,
	})

	id, err := service.StartNewFromEntitiesWithType(context.Background(), &[]uuid.UUID{uuid.New()}, EntityTypeTestsRuns)

	require.NoError(t, err)
	require.NotNil(t, id)
	assert.Len(t, testCaseRuns.inserted, 2)
	assert.Equal(t, []model.Filter{{
		Feature:   "tests_run_id",
		Condition: model.ConditionIn,
		Values:    testCaseRuns.lastSpecFilter[0].Values,
	}}, testCaseRuns.lastSpecFilter)
}

func TestCancelMarksOnlyPendingCaseRunsAsCanceled(t *testing.T) {
	testCaseRuns := &fakeTestCaseRunsRepository{}
	service, _ := testsRunsServiceOver(Repositories{TestCaseRuns: testCaseRuns})

	require.NoError(t, service.Cancel(context.Background(), uuid.New()))

	assert.Equal(t, []string{dao.RunStatusCanceled}, testCaseRuns.statusUpdates)
}

func TestBulkCancelIgnoresAnEmptyIdList(t *testing.T) {
	testCaseRuns := &fakeTestCaseRunsRepository{}
	service, _ := testsRunsServiceOver(Repositories{TestCaseRuns: testCaseRuns})

	require.NoError(t, service.BulkCancel(context.Background(), &[]uuid.UUID{}))
	require.NoError(t, service.BulkCancel(context.Background(), nil))

	assert.Empty(t, testCaseRuns.statusUpdates)
}
