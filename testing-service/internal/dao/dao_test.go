package dao

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
)

type stubDB struct{}

func (stubDB) GetBunDb(context.Context) (*bun.DB, error) { return nil, nil }

// normalizedDeps is what NewDao is always handed: the entry point fills the
// optional infrastructure in once, so nothing below it guards against a nil.
func normalizedDeps() config.Deps {
	return config.Deps{DB: stubDB{}}.WithDefaults()
}

func TestNewDaoWiresEveryRepository(t *testing.T) {
	dao := NewDao(config.Config{}.WithDefaults(), normalizedDeps())

	assert.NotNil(t, dao.TestsRuns)
	assert.NotNil(t, dao.TestCases)
	assert.NotNil(t, dao.TestCaseRuns)
	assert.NotNil(t, dao.TestCaseRunErrors)
	assert.NotNil(t, dao.TriggerReferences)
	assert.NotNil(t, dao.RequestSettings)
	assert.NotNil(t, dao.Messages)
	assert.NotNil(t, dao.Headers)
	assert.NotNil(t, dao.QueryParameters)
	assert.NotNil(t, dao.PathParameters)
	assert.NotNil(t, dao.Matchers)
	assert.NotNil(t, dao.MatcherParameters)
	assert.NotNil(t, dao.EndpointMocks)
	assert.NotNil(t, dao.EndpointReferences)
	assert.NotNil(t, dao.ResponseSettings)
}

func TestNewDaoTakesThePaginationLimitFromTheConfig(t *testing.T) {
	dao := NewDao(config.Config{PaginationLimit: 250}, normalizedDeps())

	assert.Equal(t, 250, dao.TestCases.(*testCasesRepository).paginationLimit)
	assert.Equal(t, 250, dao.EndpointMocks.(*endpointMocksRepository).paginationLimit)
	assert.Equal(t, 250, dao.TestsRuns.(*testsRunsRepository).paginationLimit)
	assert.Equal(t, 250, dao.TestCaseRuns.(*testCaseRunsRepository).paginationLimit)
}

func TestNewDaoPassesTheDefaultPaginationLimitOnToTheRepositories(t *testing.T) {
	dao := NewDao(config.Config{}.WithDefaults(), normalizedDeps())

	assert.Equal(t, config.DefaultPaginationLimit, dao.TestCases.(*testCasesRepository).paginationLimit)
}

func TestNewDaoPassesTheLoggerOnToTheRepositories(t *testing.T) {
	logger := discardLogger()

	dao := NewDao(config.Config{}.WithDefaults(), config.Deps{DB: stubDB{}, Logger: logger})

	assert.Same(t, logger, dao.logger)
	require.Same(t, logger, dao.TestCases.(*testCasesRepository).logger)
}

func TestBeforeAppendModelStampsTheAuditColumnsOnInsert(t *testing.T) {
	metadata := &Metadata{}

	// A typed nil is enough: the hook only switches on the query type.
	require.NoError(t, metadata.BeforeAppendModel(context.Background(), (*bun.InsertQuery)(nil)))

	require.NotNil(t, metadata.CreatedAt)
	require.NotNil(t, metadata.CreatedBy)
	require.NotNil(t, metadata.UpdatedAt)
	require.NotNil(t, metadata.UpdatedBy)
	assert.Equal(t, *metadata.CreatedBy, *metadata.UpdatedBy)
}

func TestBeforeAppendModelKeepsTheOriginalAuthorOnInsert(t *testing.T) {
	created := time.Date(2020, time.January, 1, 0, 0, 0, 0, time.UTC)
	author := "importer"
	metadata := &Metadata{CreatedAt: &created, CreatedBy: &author}

	require.NoError(t, metadata.BeforeAppendModel(context.Background(), (*bun.InsertQuery)(nil)))

	assert.Equal(t, created, *metadata.CreatedAt)
	assert.Equal(t, author, *metadata.CreatedBy)
	assert.NotEqual(t, created, *metadata.UpdatedAt)
}

func TestBeforeAppendModelTouchesOnlyTheUpdateColumnsOnUpdate(t *testing.T) {
	metadata := &Metadata{}

	require.NoError(t, metadata.BeforeAppendModel(context.Background(), (*bun.UpdateQuery)(nil)))

	assert.Nil(t, metadata.CreatedAt)
	assert.Nil(t, metadata.CreatedBy)
	require.NotNil(t, metadata.UpdatedAt)
	require.NotNil(t, metadata.UpdatedBy)
}

func TestBeforeAppendModelIgnoresOtherQueries(t *testing.T) {
	metadata := &Metadata{}

	require.NoError(t, metadata.BeforeAppendModel(context.Background(), (*bun.SelectQuery)(nil)))

	assert.Nil(t, metadata.UpdatedAt)
	assert.Nil(t, metadata.UpdatedBy)
}

func TestBeforeAppendModelRecordsTheUserFromTheContextOnInsert(t *testing.T) {
	metadata := &Metadata{}
	ctx := WithCurrentUser(context.Background(), "alice")

	require.NoError(t, metadata.BeforeAppendModel(ctx, (*bun.InsertQuery)(nil)))

	require.NotNil(t, metadata.CreatedBy)
	assert.Equal(t, "alice", *metadata.CreatedBy)
	assert.Equal(t, "alice", *metadata.UpdatedBy)
}

func TestBeforeAppendModelRecordsTheUserFromTheContextOnUpdate(t *testing.T) {
	metadata := &Metadata{}
	ctx := WithCurrentUser(context.Background(), "alice")

	require.NoError(t, metadata.BeforeAppendModel(ctx, (*bun.UpdateQuery)(nil)))

	require.NotNil(t, metadata.UpdatedBy)
	assert.Equal(t, "alice", *metadata.UpdatedBy)
}

func TestBeforeAppendModelFallsBackToTheDefaultUser(t *testing.T) {
	metadata := &Metadata{}

	require.NoError(t, metadata.BeforeAppendModel(context.Background(), (*bun.InsertQuery)(nil)))

	require.NotNil(t, metadata.CreatedBy)
	assert.Equal(t, DefaultUser, *metadata.CreatedBy)
}

func TestCurrentUserFallsBackWhenTheContextCarriesNoUsableUser(t *testing.T) {
	assert.Equal(t, DefaultUser, CurrentUser(context.Background()))
	assert.Equal(t, DefaultUser, CurrentUser(WithCurrentUser(context.Background(), "")))
}

func TestWithCurrentUserOverridesAnEarlierUser(t *testing.T) {
	ctx := WithCurrentUser(WithCurrentUser(context.Background(), "alice"), "bob")

	assert.Equal(t, "bob", CurrentUser(ctx))
}
