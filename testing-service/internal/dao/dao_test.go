package dao

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
)

type stubDB struct{}

func (stubDB) GetBunDb(context.Context) (*bun.DB, error) { return nil, nil }

func TestNewDaoWiresEveryRepository(t *testing.T) {
	dao := NewDao(config.Config{}, config.Deps{DB: stubDB{}})

	assert.NotNil(t, dao.TestsRunsRepository)
	assert.NotNil(t, dao.TestCasesRepository)
	assert.NotNil(t, dao.TestCaseRunsRepository)
	assert.NotNil(t, dao.TestCaseRunErrorsRepository)
	assert.NotNil(t, dao.TriggerReferencesRepository)
	assert.NotNil(t, dao.RequestSettingsRepository)
	assert.NotNil(t, dao.MessagesRepository)
	assert.NotNil(t, dao.HeadersRepository)
	assert.NotNil(t, dao.QueryParametersRepository)
	assert.NotNil(t, dao.PathParametersRepository)
	assert.NotNil(t, dao.MatchersRepository)
	assert.NotNil(t, dao.MatcherParametersRepository)
	assert.NotNil(t, dao.EndpointMocksRepository)
	assert.NotNil(t, dao.EndpointReferencesRepository)
	assert.NotNil(t, dao.ResponseSettingsRepository)
}

func TestNewDaoTakesThePaginationLimitFromTheConfig(t *testing.T) {
	dao := NewDao(config.Config{PaginationLimit: 250}, config.Deps{DB: stubDB{}})

	assert.Equal(t, 250, dao.TestCasesRepository.(*testCasesRepository).paginationLimit)
	assert.Equal(t, 250, dao.EndpointMocksRepository.(*endpointMocksRepository).paginationLimit)
	assert.Equal(t, 250, dao.TestsRunsRepository.(*testsRunsRepository).paginationLimit)
	assert.Equal(t, 250, dao.TestCaseRunsRepository.(*testCaseRunsRepository).paginationLimit)
}

func TestNewDaoFallsBackToTheDefaultPaginationLimit(t *testing.T) {
	dao := NewDao(config.Config{}, config.Deps{DB: stubDB{}})

	assert.Equal(t, config.DefaultPaginationLimit, dao.TestCasesRepository.(*testCasesRepository).paginationLimit)
}

func TestNewDaoSubstitutesALoggerWhenTheHostSuppliesNone(t *testing.T) {
	dao := NewDao(config.Config{}, config.Deps{DB: stubDB{}})

	require.NotNil(t, dao.logger)
	assert.NotNil(t, dao.TestCasesRepository.(*testCasesRepository).logger)
}

func TestNewDaoKeepsTheSuppliedLogger(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	dao := NewDao(config.Config{}, config.Deps{DB: stubDB{}, Logger: logger})

	assert.Same(t, logger, dao.logger)
}

func TestGetDbRejectsAContextThatNeverWentThroughRun(t *testing.T) {
	db, err := GetDb(context.Background())

	require.Error(t, err)
	assert.Nil(t, db)
	assert.ErrorContains(t, err, "dao.Run")
}

func TestCreateDbContextKeepsTheHandleAlreadyInTheContext(t *testing.T) {
	first := &bun.DB{}
	ctx, err := createDbContext(context.Background(), first)
	require.NoError(t, err)

	again, err := createDbContext(ctx, &bun.DB{})
	require.NoError(t, err)

	handle, err := GetDb(again)
	require.NoError(t, err)
	assert.Same(t, first, handle)
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
