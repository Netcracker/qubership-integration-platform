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

func testsRunsServiceOver(repositories dao.Repositories) (*testsRunsService, *fakeRunner) {
	service, runner, _ := testsRunsServiceWithNotifier(repositories)
	return service, runner
}

func testsRunsServiceWithNotifier(repositories dao.Repositories) (*testsRunsService, *fakeRunner, *fakeWorkNotifier) {
	runner := &fakeRunner{}
	notifier := &fakeWorkNotifier{}
	service := testsRunsServiceWith(config.Config{}, runner, repositories, notifier)
	return service, runner, notifier
}

// testsRunsServiceWith wires the service over settings of the caller's choosing,
// which is what the retention tests vary.
func testsRunsServiceWith(
	cfg config.Config,
	runner dao.Runner,
	repositories dao.Repositories,
	notifier WorkNotifier,
) *testsRunsService {
	logger := discardLogger()
	testCaseRunsService := NewTestCaseRunsService(cfg, runner, repositories)
	return NewTestsRunsService(cfg, logger, runner, repositories, testCaseRunsService, notifier).(*testsRunsService)
}

func TestStartNewRejectsAnEmptyTestCaseList(t *testing.T) {
	service, runner := testsRunsServiceOver(dao.Repositories{})

	for _, ids := range []*[]uuid.UUID{nil, {}} {
		id, err := service.startNew(context.Background(), ids)

		require.ErrorIs(t, err, ErrEmptyTestCaseList)
		assert.Nil(t, id)
	}
	assert.Zero(t, runner.txCalls, "no transaction should be opened for an empty list")
}

func TestStartNewRejectsATestCaseThatDoesNotExist(t *testing.T) {
	known := uuid.New()
	unknown := uuid.New()
	service, _ := testsRunsServiceOver(dao.Repositories{
		TestCases:    &fakeTestCasesRepository{existing: map[uuid.UUID]*dao.TestCaseView{known: {}}},
		TestsRuns:    &fakeTestsRunsRepository{},
		TestCaseRuns: &fakeTestCaseRunsRepository{},
	})

	id, err := service.startNew(context.Background(), &[]uuid.UUID{known, unknown})

	// The id came off the request body, so the caller reads a 400 with the id in
	// it rather than a 500 about this service.
	require.ErrorIs(t, err, ErrInvalidRequest)
	assert.ErrorContains(t, err, unknown.String())
	assert.Nil(t, id)
}

func TestStartNewRejectsAnEntityTypeItCannotResolve(t *testing.T) {
	service, runner := testsRunsServiceOver(dao.Repositories{})

	id, err := service.StartNewFromEntitiesWithType(context.Background(), &[]uuid.UUID{uuid.New()}, "chains")

	require.ErrorIs(t, err, ErrInvalidRequest)
	assert.ErrorContains(t, err, "chains")
	assert.Nil(t, id)
	assert.Zero(t, runner.txCalls, "no transaction should be opened for an entity type that resolves to nothing")
}

func TestStartNewQueuesOneCaseRunPerTestCase(t *testing.T) {
	first := uuid.New()
	second := uuid.New()
	testCaseRuns := &fakeTestCaseRunsRepository{}
	testsRuns := &fakeTestsRunsRepository{}
	service, _ := testsRunsServiceOver(dao.Repositories{
		TestCases:    &fakeTestCasesRepository{existing: map[uuid.UUID]*dao.TestCaseView{first: {}, second: {}}},
		TestsRuns:    testsRuns,
		TestCaseRuns: testCaseRuns,
	})

	id, err := service.startNew(context.Background(), &[]uuid.UUID{first, second})

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
	service, _ := testsRunsServiceOver(dao.Repositories{
		TestCases: &fakeTestCasesRepository{
			existing: map[uuid.UUID]*dao.TestCaseView{first: {}, second: {}, third: {}},
		},
		TestsRuns:    &fakeTestsRunsRepository{},
		TestCaseRuns: testCaseRuns,
	})

	_, err := service.startNew(context.Background(), &[]uuid.UUID{first, second, third})

	require.NoError(t, err)
	require.Len(t, testCaseRuns.inserted, 3)
	ordinals := make([]int, 0, len(testCaseRuns.inserted))
	for _, testCaseRun := range testCaseRuns.inserted {
		require.NotNil(t, testCaseRun.Ordinal, "the claim orders by ordinal, so it may not be left unset")
		ordinals = append(ordinals, *testCaseRun.Ordinal)
	}
	assert.Equal(t, []int{1, 2, 3}, ordinals)
}

func TestStartNewSignalsTheExecutorOnceTheCasesAreQueued(t *testing.T) {
	testCaseID := uuid.New()
	service, _, notifier := testsRunsServiceWithNotifier(dao.Repositories{
		TestCases:    &fakeTestCasesRepository{existing: map[uuid.UUID]*dao.TestCaseView{testCaseID: {}}},
		TestsRuns:    &fakeTestsRunsRepository{},
		TestCaseRuns: &fakeTestCaseRunsRepository{},
	})

	_, err := service.startNew(context.Background(), &[]uuid.UUID{testCaseID})

	require.NoError(t, err)
	assert.Equal(t, int32(1), notifier.signals.Load(), "a worker should not wait out a poll interval")
}

func TestStartNewSignalsNothingWhenTheRunWasNotQueued(t *testing.T) {
	known := uuid.New()
	service, _, notifier := testsRunsServiceWithNotifier(dao.Repositories{
		TestCases:    &fakeTestCasesRepository{existing: map[uuid.UUID]*dao.TestCaseView{known: {}}},
		TestsRuns:    &fakeTestsRunsRepository{},
		TestCaseRuns: &fakeTestCaseRunsRepository{},
	})

	_, err := service.startNew(context.Background(), &[]uuid.UUID{known, uuid.New()})

	require.Error(t, err)
	assert.Zero(t, notifier.signals.Load())
}

func TestStartNewFromEntitiesRejectsAnUnknownEntityType(t *testing.T) {
	service, _ := testsRunsServiceOver(dao.Repositories{})

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
	service, _ := testsRunsServiceOver(dao.Repositories{
		TestCases:    &fakeTestCasesRepository{existing: map[uuid.UUID]*dao.TestCaseView{shared: {}, other: {}}},
		TestsRuns:    testsRuns,
		TestCaseRuns: testCaseRuns,
	})

	testsRunIds := []uuid.UUID{uuid.New(), uuid.New()}

	id, err := service.StartNewFromEntitiesWithType(context.Background(), &testsRunIds, RunSourceTestsRuns)

	require.NoError(t, err)
	require.NotNil(t, id)
	assert.Len(t, testCaseRuns.inserted, 2)
	assert.Equal(t, []model.Filter{{
		Feature:   "tests_run_id",
		Condition: model.ConditionIn,
		Values:    uuid.UUIDs(testsRunIds).Strings(),
	}}, testCaseRuns.lastSpecFilter)
}

// idWith builds an id starting with the given block, so a test can lay out ids
// whose byte order is the reverse of the order under test.
func idWith(t *testing.T, block string) uuid.UUID {
	t.Helper()
	id, err := uuid.Parse(block + "-1111-4111-8111-111111111111")
	require.NoError(t, err)
	return id
}

// startedTestCaseIds reports the test cases of the queued run in ordinal order.
func startedTestCaseIds(t *testing.T, testCaseRuns *fakeTestCaseRunsRepository) []uuid.UUID {
	t.Helper()
	byOrdinal := make([]uuid.UUID, len(testCaseRuns.inserted))
	for _, testCaseRun := range testCaseRuns.inserted {
		require.NotNil(t, testCaseRun.Ordinal)
		position := *testCaseRun.Ordinal - 1
		require.GreaterOrEqual(t, position, 0)
		require.Less(t, position, len(byOrdinal))
		require.NotNil(t, testCaseRun.TestCaseID)
		byOrdinal[position] = *testCaseRun.TestCaseID
	}
	return byOrdinal
}

// A rerun of whole test runs has to repeat what those runs did: the runs in the
// order the caller listed them, and the cases of each run in the order they ran.
// The listing behind it answers in no particular order, so the ids below are laid
// out in descending byte order and the runs are listed likewise.
func TestStartNewFromTestsRunsFollowsTheOrderTheCasesRanIn(t *testing.T) {
	firstRun, secondRun := idWith(t, "22222222"), idWith(t, "11111111")
	first := idWith(t, "dddddddd")
	second := idWith(t, "cccccccc")
	third := idWith(t, "bbbbbbbb")
	fourth := idWith(t, "aaaaaaaa")

	ordinal := func(value int) *int { return &value }
	run := func(id uuid.UUID) *uuid.UUID { return &id }
	// The rows come back scrambled, which is what the listing may do.
	testCaseRuns := &fakeTestCaseRunsRepository{views: []dao.TestCaseRunView{
		{TestCaseRun: dao.TestCaseRun{TestCaseID: &fourth, TestsRunID: run(secondRun), Ordinal: ordinal(2)}},
		{TestCaseRun: dao.TestCaseRun{TestCaseID: &second, TestsRunID: run(firstRun), Ordinal: ordinal(2)}},
		{TestCaseRun: dao.TestCaseRun{TestCaseID: &third, TestsRunID: run(secondRun), Ordinal: ordinal(1)}},
		{TestCaseRun: dao.TestCaseRun{TestCaseID: &first, TestsRunID: run(firstRun), Ordinal: ordinal(1)}},
	}}
	service, _ := testsRunsServiceOver(dao.Repositories{
		TestCases: &fakeTestCasesRepository{existing: map[uuid.UUID]*dao.TestCaseView{
			first: {}, second: {}, third: {}, fourth: {},
		}},
		TestsRuns:    &fakeTestsRunsRepository{},
		TestCaseRuns: testCaseRuns,
	})

	_, err := service.StartNewFromEntitiesWithType(
		context.Background(), &[]uuid.UUID{firstRun, secondRun}, RunSourceTestsRuns)

	require.NoError(t, err)
	require.Len(t, testCaseRuns.inserted, 4)
	assert.Equal(t, []uuid.UUID{first, second, third, fourth}, startedTestCaseIds(t, testCaseRuns),
		"an order-dependent suite has to rerun in the order it ran in, not in id order")
}

// A rerun of individual case runs follows the order the caller listed them in.
func TestStartNewFromTestCaseRunsFollowsTheOrderTheCallerListed(t *testing.T) {
	firstCaseRun, secondCaseRun, thirdCaseRun := idWith(t, "11111111"), idWith(t, "22222222"), idWith(t, "33333333")
	first := idWith(t, "cccccccc")
	second := idWith(t, "bbbbbbbb")
	third := idWith(t, "aaaaaaaa")

	testCaseRuns := &fakeTestCaseRunsRepository{views: []dao.TestCaseRunView{
		{TestCaseRun: dao.TestCaseRun{ID: thirdCaseRun, TestCaseID: &third}},
		{TestCaseRun: dao.TestCaseRun{ID: firstCaseRun, TestCaseID: &first}},
		{TestCaseRun: dao.TestCaseRun{ID: secondCaseRun, TestCaseID: &second}},
	}}
	service, _ := testsRunsServiceOver(dao.Repositories{
		TestCases: &fakeTestCasesRepository{existing: map[uuid.UUID]*dao.TestCaseView{
			first: {}, second: {}, third: {},
		}},
		TestsRuns:    &fakeTestsRunsRepository{},
		TestCaseRuns: testCaseRuns,
	})

	_, err := service.StartNewFromEntitiesWithType(
		context.Background(),
		&[]uuid.UUID{firstCaseRun, secondCaseRun, thirdCaseRun},
		RunSourceTestCaseRuns,
	)

	require.NoError(t, err)
	require.Len(t, testCaseRuns.inserted, 3)
	assert.Equal(t, []uuid.UUID{first, second, third}, startedTestCaseIds(t, testCaseRuns))
}

// An empty selection is a selection of nothing, whatever it is a selection of.
// The listing behind a rerun answers an empty id list with every row it has, so
// a request naming no entity has to be refused before it is issued: otherwise a
// rerun of nothing becomes a run over every test case in the installation, each
// one firing real requests at a live chain.
func TestStartNewRejectsAnEmptySelectionOfEveryEntityType(t *testing.T) {
	for _, entityType := range []string{RunSourceTestCases, RunSourceTestCaseRuns, RunSourceTestsRuns} {
		t.Run(entityType, func(t *testing.T) {
			existing := uuid.New()
			// The repository holds a case run the listing would return for an
			// unnarrowed query, which is what an unguarded empty list would run.
			testCaseRuns := &fakeTestCaseRunsRepository{
				views: []dao.TestCaseRunView{{TestCaseRun: dao.TestCaseRun{ID: uuid.New(), TestCaseID: &existing}}},
			}
			service, runner := testsRunsServiceOver(dao.Repositories{
				TestCases:    &fakeTestCasesRepository{existing: map[uuid.UUID]*dao.TestCaseView{existing: {}}},
				TestsRuns:    &fakeTestsRunsRepository{},
				TestCaseRuns: testCaseRuns,
			})

			id, err := service.StartNewFromEntitiesWithType(context.Background(), &[]uuid.UUID{}, entityType)

			require.ErrorIs(t, err, ErrEmptyTestCaseList)
			assert.Nil(t, id)
			assert.Empty(t, testCaseRuns.inserted)
			assert.Zero(t, runner.txCalls, "no transaction should be opened for an empty selection")
		})
	}
}

func TestCancelMarksOnlyPendingCaseRunsAsCanceled(t *testing.T) {
	testCaseRuns := &fakeTestCaseRunsRepository{}
	service, _ := testsRunsServiceOver(dao.Repositories{TestCaseRuns: testCaseRuns})
	testsRunID := uuid.New()

	require.NoError(t, service.Cancel(context.Background(), testsRunID))

	assert.Equal(t, []string{dao.RunStatusCanceled}, testCaseRuns.statusUpdates)
	// The selector is what keeps a running or a finished case out of the update,
	// so assert on the SQL it builds rather than on the status alone.
	where := testCaseRuns.lastStatusSelector(t)
	assert.Contains(t, where, testsRunID.String())
	assert.Contains(t, where, "tests_run_id IN")
	assert.Contains(t, where, "status = 'pending'")
}

func TestBulkCancelSelectsTheNamedRunsAndOnlyThePendingCases(t *testing.T) {
	testCaseRuns := &fakeTestCaseRunsRepository{}
	service, _ := testsRunsServiceOver(dao.Repositories{TestCaseRuns: testCaseRuns})
	ids := []uuid.UUID{uuid.New(), uuid.New()}

	require.NoError(t, service.BulkCancel(context.Background(), &ids))

	assert.Equal(t, []string{dao.RunStatusCanceled}, testCaseRuns.statusUpdates)
	where := testCaseRuns.lastStatusSelector(t)
	for _, id := range ids {
		assert.Contains(t, where, id.String())
	}
	assert.Contains(t, where, "tests_run_id IN")
	assert.Contains(t, where, "status = 'pending'")
}

func TestCancelAnIndividualCaseRunSelectsItByIdAndOnlyWhilePending(t *testing.T) {
	testCaseRuns := &fakeTestCaseRunsRepository{}
	service := NewTestCaseRunsService(
		config.Config{}.WithDefaults(), &fakeRunner{}, dao.Repositories{TestCaseRuns: testCaseRuns})
	id := uuid.New()

	require.NoError(t, service.Cancel(context.Background(), id))

	where := testCaseRuns.lastStatusSelector(t)
	assert.Contains(t, where, "id = '"+id.String()+"'")
	assert.Contains(t, where, "status = 'pending'")
}

func TestBulkCancelIgnoresAnEmptyIdList(t *testing.T) {
	testCaseRuns := &fakeTestCaseRunsRepository{}
	service, _ := testsRunsServiceOver(dao.Repositories{TestCaseRuns: testCaseRuns})

	require.NoError(t, service.BulkCancel(context.Background(), &[]uuid.UUID{}))
	require.NoError(t, service.BulkCancel(context.Background(), nil))

	assert.Empty(t, testCaseRuns.statusUpdates)
}
