package services

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/matching"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// testCasesFixture wires the service over stub repositories and hands the test
// every one of them, so an assertion can name the exact table a write landed in.
type testCasesFixture struct {
	service           TestCasesService
	logs              *bytes.Buffer
	runner            *fakeRunner
	testCases         *fakeTestCasesRepository
	triggerReferences *fakeTriggerReferencesRepository
	requestSettings   *fakeRequestSettingsRepository
	messages          *fakeMessagesRepository
	headers           *fakeHeadersRepository
	pathParameters    *fakePathParametersRepository
	queryParameters   *fakeQueryParametersRepository
	matchers          *fakeMatchersRepository
	matcherParameters *fakeMatcherParametersRepository
}

func newTestCasesFixture() *testCasesFixture {
	f := &testCasesFixture{
		logs:              &bytes.Buffer{},
		runner:            &fakeRunner{},
		testCases:         &fakeTestCasesRepository{existing: map[uuid.UUID]*dao.TestCaseView{}},
		triggerReferences: &fakeTriggerReferencesRepository{},
		requestSettings:   &fakeRequestSettingsRepository{},
		messages:          &fakeMessagesRepository{},
		headers:           &fakeHeadersRepository{},
		pathParameters:    &fakePathParametersRepository{},
		queryParameters:   &fakeQueryParametersRepository{},
		matchers:          &fakeMatchersRepository{},
		matcherParameters: &fakeMatcherParametersRepository{},
	}
	f.service = NewTestCasesService(slog.New(slog.NewTextHandler(f.logs, nil)), f.runner, dao.Repositories{
		TestCases:         f.testCases,
		TriggerReferences: f.triggerReferences,
		RequestSettings:   f.requestSettings,
		Messages:          f.messages,
		Headers:           f.headers,
		PathParameters:    f.pathParameters,
		QueryParameters:   f.queryParameters,
		Matchers:          f.matchers,
		MatcherParameters: f.matcherParameters,
	})
	return f
}

func fullTestCase() *dao.TestCase {
	body := `{"order":1}`
	return &dao.TestCase{
		Name:             "order flow",
		Enabled:          true,
		TriggerReference: &dao.TriggerReference{ChainID: "chain-1", ElementID: "element-1"},
		RequestSettings: &dao.RequestSettings{
			Method:  http.MethodPost,
			Timeout: 5000,
			Message: &dao.Message{
				Body:    &body,
				Headers: []*dao.Header{{Name: "Content-Type", Value: "application/json"}, nil},
			},
			PathParameters:  []*dao.PathParameter{{Name: "id", Value: "42"}, nil},
			QueryParameters: []*dao.QueryParameter{{Name: "status", Value: "new"}, nil},
		},
		// A validation rule is built on save, so the fixture carries one that can
		// actually be built.
		ResponseValidationRules: []*dao.Matcher{
			{Name: "status is 200", Enabled: true, Type: "exist", EntityType: matching.EntityTypeStatus},
		},
	}
}

func TestCreateLinksEveryChildToTheStoredTestCase(t *testing.T) {
	f := newTestCasesFixture()

	created, err := f.service.Create(context.Background(), fullTestCase())

	require.NoError(t, err)
	require.Len(t, f.testCases.inserted, 1)
	id := f.testCases.inserted[0].ID
	assert.Equal(t, id, created.ID)
	assert.Equal(t, 1, f.runner.txCalls, "the test case and its children go in together or not at all")

	require.Len(t, f.triggerReferences.inserted, 1)
	assert.Equal(t, id, f.triggerReferences.inserted[0].TestCaseID)

	require.Len(t, f.requestSettings.inserted, 1)
	assert.Equal(t, id, f.requestSettings.inserted[0].TestCaseID)

	settingsID := f.requestSettings.inserted[0].ID
	require.Len(t, f.messages.inserted, 1)
	assert.Equal(t, settingsID, f.messages.inserted[0].OwnerID)
	require.Len(t, f.headers.batches, 1)
	require.Len(t, f.headers.batches[0], 1, "a nil header is skipped")
	assert.Equal(t, f.messages.inserted[0].ID, f.headers.batches[0][0].MessageID)
	require.Len(t, f.pathParameters.batches[0], 1)
	assert.Equal(t, settingsID, f.pathParameters.batches[0][0].RequestSettingsID)
	require.Len(t, f.queryParameters.batches[0], 1)
	assert.Equal(t, settingsID, f.queryParameters.batches[0][0].RequestSettingsID)

	require.Len(t, f.matchers.inserted, 1)
	assert.Equal(t, id, f.matchers.inserted[0].OwnerID)
}

func TestCreateStoresATestCaseWithoutChildren(t *testing.T) {
	f := newTestCasesFixture()

	created, err := f.service.Create(context.Background(), &dao.TestCase{Name: "bare"})

	require.NoError(t, err)
	assert.Nil(t, created.TriggerReference)
	assert.Nil(t, created.RequestSettings)
	assert.Empty(t, f.triggerReferences.inserted)
	assert.Empty(t, f.requestSettings.inserted)
}

func TestCreateReportsAFailingInsert(t *testing.T) {
	failure := errors.New("constraint violated")
	f := newTestCasesFixture()
	f.testCases.insertErr = failure

	created, err := f.service.Create(context.Background(), fullTestCase())

	require.ErrorIs(t, err, failure)
	assert.Nil(t, created)
}

func TestUpdateRejectsATestCaseThatIsGone(t *testing.T) {
	f := newTestCasesFixture()

	id := uuid.New()

	updated, err := f.service.Update(context.Background(), &dao.TestCase{ID: id})

	// The sentinel is what the controller answers 404 to. Without it the caller
	// reads a 500 about this service over an id of its own.
	require.ErrorIs(t, err, ErrNotFound)
	assert.ErrorContains(t, err, id.String())
	assert.Nil(t, updated)
	assert.Empty(t, f.testCases.updated)
}

// The unique, not-null test_case_id has no default and is json:"-", so settings
// added to a test case that had none used to be inserted under the zero UUID and
// fail on the foreign key.
func TestUpdateStampsTheOwnerOnRequestSettingsAddedToATestCaseThatHadNone(t *testing.T) {
	f := newTestCasesFixture()
	id := uuid.New()
	f.testCases.existing[id] = &dao.TestCaseView{TestCase: dao.TestCase{ID: id, Name: "bare"}}

	updated, err := f.service.Update(context.Background(), &dao.TestCase{
		ID:              id,
		Name:            "bare",
		RequestSettings: &dao.RequestSettings{Method: http.MethodGet},
	})

	require.NoError(t, err)
	require.NotNil(t, updated.RequestSettings)
	require.Len(t, f.requestSettings.inserted, 1)
	assert.Equal(t, id, f.requestSettings.inserted[0].TestCaseID)
	assert.NotEqual(t, uuid.Nil, f.requestSettings.inserted[0].TestCaseID)
	assert.Empty(t, f.requestSettings.deleted, "there was nothing to replace")
}

func TestUpdateReplacesTheStoredRequestSettings(t *testing.T) {
	f := newTestCasesFixture()
	id := uuid.New()
	settingsID := uuid.New()
	f.testCases.existing[id] = &dao.TestCaseView{TestCase: dao.TestCase{
		ID:              id,
		RequestSettings: &dao.RequestSettings{ID: settingsID, Method: http.MethodGet},
	}}

	_, err := f.service.Update(context.Background(), &dao.TestCase{
		ID:              id,
		RequestSettings: &dao.RequestSettings{Method: http.MethodPost},
	})

	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{settingsID}, f.requestSettings.deleted)
	require.Len(t, f.requestSettings.inserted, 1)
	assert.Equal(t, settingsID, f.requestSettings.inserted[0].ID, "the replacement keeps the row id")
	assert.Equal(t, id, f.requestSettings.inserted[0].TestCaseID)
}

func TestUpdateDropsRequestSettingsTheRequestLeftOut(t *testing.T) {
	f := newTestCasesFixture()
	id := uuid.New()
	settingsID := uuid.New()
	f.testCases.existing[id] = &dao.TestCaseView{TestCase: dao.TestCase{
		ID:              id,
		RequestSettings: &dao.RequestSettings{ID: settingsID},
	}}

	updated, err := f.service.Update(context.Background(), &dao.TestCase{ID: id})

	require.NoError(t, err)
	assert.Nil(t, updated.RequestSettings)
	assert.Equal(t, []uuid.UUID{settingsID}, f.requestSettings.deleted)
	assert.Empty(t, f.requestSettings.inserted)
}

func TestUpdateStampsTheOwnerOnATriggerReferenceAddedToATestCaseThatHadNone(t *testing.T) {
	f := newTestCasesFixture()
	id := uuid.New()
	f.testCases.existing[id] = &dao.TestCaseView{TestCase: dao.TestCase{ID: id}}

	_, err := f.service.Update(context.Background(), &dao.TestCase{
		ID:               id,
		TriggerReference: &dao.TriggerReference{ChainID: "chain-1", ElementID: "element-1"},
	})

	require.NoError(t, err)
	require.Len(t, f.triggerReferences.inserted, 1)
	assert.Equal(t, id, f.triggerReferences.inserted[0].TestCaseID)
}

func TestUpdateUpdatesTheStoredTriggerReferenceInPlace(t *testing.T) {
	f := newTestCasesFixture()
	id := uuid.New()
	referenceID := uuid.New()
	f.testCases.existing[id] = &dao.TestCaseView{TestCase: dao.TestCase{
		ID:               id,
		TriggerReference: &dao.TriggerReference{ID: referenceID, ChainID: "old"},
	}}

	_, err := f.service.Update(context.Background(), &dao.TestCase{
		ID:               id,
		TriggerReference: &dao.TriggerReference{ChainID: "new", ElementID: "element-1"},
	})

	require.NoError(t, err)
	assert.Empty(t, f.triggerReferences.inserted)
	require.Len(t, f.triggerReferences.updated, 1)
	assert.Equal(t, referenceID, f.triggerReferences.updated[0].ID)
	assert.Equal(t, id, f.triggerReferences.updated[0].TestCaseID)
	assert.Equal(t, "new", f.triggerReferences.updated[0].ChainID)
}

func TestUpdateDeletesATriggerReferenceTheRequestLeftOut(t *testing.T) {
	f := newTestCasesFixture()
	id := uuid.New()
	referenceID := uuid.New()
	f.testCases.existing[id] = &dao.TestCaseView{TestCase: dao.TestCase{
		ID:               id,
		TriggerReference: &dao.TriggerReference{ID: referenceID},
	}}

	updated, err := f.service.Update(context.Background(), &dao.TestCase{ID: id})

	require.NoError(t, err)
	assert.Nil(t, updated.TriggerReference)
	assert.Equal(t, []uuid.UUID{referenceID}, f.triggerReferences.deleted)
}

// The rules are replaced wholesale, so the old ones have to go before the new
// ones are stored under the same owner.
func TestUpdateReplacesTheValidationRules(t *testing.T) {
	f := newTestCasesFixture()
	id := uuid.New()
	f.testCases.existing[id] = &dao.TestCaseView{TestCase: dao.TestCase{ID: id}}

	updated, err := f.service.Update(context.Background(), &dao.TestCase{
		ID: id,
		ResponseValidationRules: []*dao.Matcher{
			{Name: "first", Type: "exist", EntityType: matching.EntityTypeBody},
			{Name: "second", Type: "exist", EntityType: matching.EntityTypeBody},
		},
	})

	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{id}, f.matchers.deletedByOwner)
	require.Len(t, f.matchers.inserted, 2)
	assert.Equal(t, id, f.matchers.inserted[0].OwnerID)
	assert.Len(t, updated.ResponseValidationRules, 2)
}

func TestDeleteTakesTheMatchersWithIt(t *testing.T) {
	f := newTestCasesFixture()
	id := uuid.New()

	require.NoError(t, f.service.Delete(context.Background(), id))

	assert.Equal(t, []uuid.UUID{id}, f.testCases.deleted)
	assert.Equal(t, []uuid.UUID{id}, f.matchers.deletedByOwner)
	assert.Equal(t, 1, f.runner.txCalls)
}

func TestBulkDeleteLeavesTheMatchersToTheDatabaseTrigger(t *testing.T) {
	f := newTestCasesFixture()
	ids := []uuid.UUID{uuid.New(), uuid.New()}

	require.NoError(t, f.service.BulkDelete(context.Background(), &ids))

	require.Len(t, f.testCases.bulkDeletes, 1)
	assert.Equal(t, ids, f.testCases.bulkDeletes[0])
	assert.Empty(t, f.matchers.deletedByOwner, "a statement trigger on test_cases removes them")
}

// exportedArchive reads the entities out of a zip the export produced.
func exportedArchive(t *testing.T, data []byte) []model.ExportedEntity {
	t.Helper()
	reader, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	require.NoError(t, err)
	entities := make([]model.ExportedEntity, 0, len(reader.File))
	for _, file := range reader.File {
		handle, err := file.Open()
		require.NoError(t, err)
		content, err := io.ReadAll(handle)
		require.NoError(t, handle.Close())
		require.NoError(t, err)
		var entity model.ExportedEntity
		require.NoError(t, json.Unmarshal(content, &entity))
		entities = append(entities, entity)
	}
	return entities
}

func TestExportWritesOneEntityFilePerTestCase(t *testing.T) {
	f := newTestCasesFixture()
	first := dao.TestCase{ID: uuid.New(), Name: "first", Enabled: true}
	second := dao.TestCase{ID: uuid.New(), Name: "second"}
	f.testCases.views = []dao.TestCaseView{{TestCase: first}, {TestCase: second}}

	data, err := f.service.Export(context.Background(), &[]uuid.UUID{first.ID, second.ID})

	require.NoError(t, err)
	entities := exportedArchive(t, *data)
	require.Len(t, entities, 2)
	assert.Equal(t, model.ExportedTypeTestCase, entities[0].Type)
	assert.Equal(t, first.ID, entities[0].ID)
	assert.Equal(t, "first", entities[0].Name)
	assert.Positive(t, entities[0].Version, "the archive names the version it was written at")

	var exported dao.TestCase
	require.NoError(t, json.Unmarshal(entities[0].Data, &exported))
	assert.Equal(t, first.ID, exported.ID)
	assert.True(t, exported.Enabled)
}

// An export of nothing is an empty archive. The same request used to dump every
// test case in the installation, because an empty id list narrowed the query by
// nothing at all.
func TestExportWritesAnEmptyArchiveForAnEmptyTestCaseSelection(t *testing.T) {
	f := newTestCasesFixture()
	f.testCases.views = []dao.TestCaseView{{TestCase: dao.TestCase{ID: uuid.New(), Name: "kept"}}}

	data, err := f.service.Export(context.Background(), &[]uuid.UUID{})

	require.NoError(t, err)
	assert.Empty(t, exportedArchive(t, *data))
}

func TestExportReportsAFailingQuery(t *testing.T) {
	failure := errors.New("no connection")
	f := newTestCasesFixture()
	f.testCases.findErr = failure

	data, err := f.service.Export(context.Background(), &[]uuid.UUID{uuid.New()})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, data)
}

// archiveOf builds the multipart file header an import reads, carrying the given
// entities as one zip.
func archiveOf(t *testing.T, entities ...model.ExportedEntity) *multipart.FileHeader {
	t.Helper()
	var archive bytes.Buffer
	zipWriter := zip.NewWriter(&archive)
	for _, entity := range entities {
		content, err := json.Marshal(entity)
		require.NoError(t, err)
		file, err := zipWriter.Create(entity.ID.String() + ".json")
		require.NoError(t, err)
		_, err = file.Write(content)
		require.NoError(t, err)
	}
	require.NoError(t, zipWriter.Close())

	var form bytes.Buffer
	formWriter := multipart.NewWriter(&form)
	part, err := formWriter.CreateFormFile("file", "entities.zip")
	require.NoError(t, err)
	_, err = part.Write(archive.Bytes())
	require.NoError(t, err)
	require.NoError(t, formWriter.Close())

	reader := multipart.NewReader(bytes.NewReader(form.Bytes()), formWriter.Boundary())
	parsed, err := reader.ReadForm(int64(form.Len()) + 1024)
	require.NoError(t, err)
	require.Len(t, parsed.File["file"], 1)
	return parsed.File["file"][0]
}

func exportedTestCase(t *testing.T, testCase dao.TestCase) model.ExportedEntity {
	t.Helper()
	data, err := json.Marshal(testCase)
	require.NoError(t, err)
	return model.ExportedEntity{
		Version: 1,
		Type:    model.ExportedTypeTestCase,
		ID:      testCase.ID,
		Name:    testCase.Name,
		Data:    data,
	}
}

func TestImportCreatesATestCaseTheDatabaseDoesNotHaveYet(t *testing.T) {
	f := newTestCasesFixture()
	testCase := dao.TestCase{ID: uuid.New(), Name: "imported", Enabled: true}

	results, err := f.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, exportedTestCase(t, testCase))})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultCreated, (*results)[0].Result)
	require.Len(t, f.testCases.inserted, 1)
	assert.Equal(t, testCase.ID, f.testCases.inserted[0].ID, "an import keeps the exported id")
	assert.Empty(t, f.testCases.updated)
}

func TestImportUpdatesATestCaseTheDatabaseAlreadyHas(t *testing.T) {
	f := newTestCasesFixture()
	id := uuid.New()
	f.testCases.existing[id] = &dao.TestCaseView{TestCase: dao.TestCase{ID: id, Name: "old"}}

	results, err := f.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, exportedTestCase(t, dao.TestCase{ID: id, Name: "new"}))})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultUpdated, (*results)[0].Result)
	require.Len(t, f.testCases.updated, 1)
	assert.Equal(t, "new", f.testCases.updated[0].Name)
	assert.Empty(t, f.testCases.inserted)
}

func TestImportRejectsAnEntityOfTheWrongType(t *testing.T) {
	f := newTestCasesFixture()
	entity := exportedTestCase(t, dao.TestCase{ID: uuid.New(), Name: "a mock"})
	entity.Type = model.ExportedTypeEndpointMock

	results, err := f.service.Import(context.Background(), []*multipart.FileHeader{archiveOf(t, entity)})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultError, (*results)[0].Result)
	assert.Contains(t, (*results)[0].Message, "wrong entity type")
	assert.Empty(t, f.testCases.inserted)
}

func TestImportRejectsDataFromANewerBuild(t *testing.T) {
	f := newTestCasesFixture()
	entity := exportedTestCase(t, dao.TestCase{ID: uuid.New(), Name: "from the future"})
	entity.Version = 99

	results, err := f.service.Import(context.Background(), []*multipart.FileHeader{archiveOf(t, entity)})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultError, (*results)[0].Result)
	assert.Contains(t, (*results)[0].Message, "failed to migrate data")
	assert.Empty(t, f.testCases.inserted)
}

// A response validation rule is built the way a request matcher of an endpoint
// mock is, so the same mistakes are refused on the request that stores them.
// Left to the run, the rule would be recorded as a validation error against
// every attempt instead.
func refusedValidationRules() map[string]*dao.Matcher {
	header := "X-Kind"
	blank := "  "
	spaced := "X Kind"
	slashed := "order/Id"
	return map[string]*dao.Matcher{
		// A name outside the grammar of its entity type holds like a missing one:
		// no response carries a header called `X Kind`, and no path template
		// placeholder spells `order/Id`.
		"header rule whose entity name is not an HTTP field name": {
			Name: "r", Type: "empty", EntityType: matching.EntityTypeHeader, EntityName: &spaced,
		},
		"path parameter rule whose entity name spans two segments": {
			Name: "r", Type: "empty", EntityType: matching.EntityTypePathParameter, EntityName: &slashed,
		},
		// A named entity type without a name reads nothing out of every response,
		// so a rule over it holds whatever the run returns.
		"header rule without an entity name": {
			Name: "r", Type: "empty", EntityType: matching.EntityTypeHeader,
		},
		"query parameter rule with a blank entity name": {
			Name: "r", Type: "empty", EntityType: matching.EntityTypeQueryParameter, EntityName: &blank,
		},
		"unknown matcher type": {Name: "r", Type: "sounds_like", EntityType: matching.EntityTypeBody},
		"unknown entity type":  {Name: "r", Type: "exist", EntityType: "cookie"},
		"pattern that is not a regular expression": {
			Name: "r", Type: "match", EntityType: matching.EntityTypeBody,
			Parameters: []*dao.MatcherParameter{{Name: "pattern", Value: "("}},
		},
		"missing the parameter the type requires": {
			Name: "r", Type: "equal", EntityType: matching.EntityTypeHeader, EntityName: &header,
		},
		// A rule one toggle away from being evaluated is checked like the rest.
		"disabled rule that cannot be built": {
			Name: "r", Type: "match", EntityType: matching.EntityTypeBody, Enabled: false,
			Parameters: []*dao.MatcherParameter{{Name: "pattern", Value: "["}},
		},
	}
}

func testCaseWithRule(rule *dao.Matcher) *dao.TestCase {
	return &dao.TestCase{Name: "order flow", ResponseValidationRules: []*dao.Matcher{rule}}
}

func TestCreateRefusesAValidationRuleThatCannotBeBuilt(t *testing.T) {
	for name, rule := range refusedValidationRules() {
		t.Run(name, func(t *testing.T) {
			f := newTestCasesFixture()

			created, err := f.service.Create(context.Background(), testCaseWithRule(rule))

			require.ErrorIs(t, err, ErrInvalidRequest)
			assert.Nil(t, created)
			assert.Empty(t, f.testCases.inserted, "nothing is stored for a refused test case")
		})
	}
}

func TestUpdateRefusesAValidationRuleThatCannotBeBuilt(t *testing.T) {
	for name, rule := range refusedValidationRules() {
		t.Run(name, func(t *testing.T) {
			f := newTestCasesFixture()
			id := uuid.New()
			f.testCases.existing[id] = &dao.TestCaseView{TestCase: dao.TestCase{ID: id, Name: "old"}}
			testCase := testCaseWithRule(rule)
			testCase.ID = id

			updated, err := f.service.Update(context.Background(), testCase)

			require.ErrorIs(t, err, ErrInvalidRequest)
			assert.Nil(t, updated)
			assert.Empty(t, f.testCases.updated, "nothing is stored for a refused test case")
		})
	}
}

// The refusal is about the imported file, so the importer reads what to fix
// rather than the generic message a failing save reports.
func TestImportReportsARefusedValidationRuleWithItsReason(t *testing.T) {
	f := newTestCasesFixture()
	testCase := *testCaseWithRule(&dao.Matcher{Name: "r", Type: "exist", EntityType: "cookie"})
	testCase.ID = uuid.New()

	results, err := f.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, exportedTestCase(t, testCase))})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultError, (*results)[0].Result)
	assert.Contains(t, (*results)[0].Message, "cookie")
	assert.Empty(t, f.testCases.inserted)
}

func TestImportReportsAnEntityWithAMalformedPayload(t *testing.T) {
	f := newTestCasesFixture()
	entity := exportedTestCase(t, dao.TestCase{ID: uuid.New(), Name: "broken"})
	entity.Data = json.RawMessage(`{"enabled":"not a bool"}`)

	results, err := f.service.Import(context.Background(), []*multipart.FileHeader{archiveOf(t, entity)})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultError, (*results)[0].Result)
	assert.Empty(t, f.testCases.inserted)
}

// An export and the import of what it produced have to agree, or a round trip
// through the archive loses the entity.
func TestATestCaseSurvivesAnExportAndImportRoundTrip(t *testing.T) {
	exporter := newTestCasesFixture()
	testCase := *fullTestCase()
	testCase.ID = uuid.New()
	exporter.testCases.views = []dao.TestCaseView{{TestCase: testCase}}

	data, err := exporter.service.Export(context.Background(), &[]uuid.UUID{testCase.ID})
	require.NoError(t, err)

	entities := exportedArchive(t, *data)
	require.Len(t, entities, 1)

	importer := newTestCasesFixture()
	results, err := importer.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, entities[0])})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultCreated, (*results)[0].Result)
	require.Len(t, importer.testCases.inserted, 1)
	assert.Equal(t, testCase.ID, importer.testCases.inserted[0].ID)
	assert.Equal(t, testCase.Name, importer.testCases.inserted[0].Name)
	require.Len(t, importer.triggerReferences.inserted, 1)
	assert.Equal(t, "chain-1", importer.triggerReferences.inserted[0].ChainID)
	require.Len(t, importer.requestSettings.inserted, 1)
	assert.Equal(t, http.MethodPost, importer.requestSettings.inserted[0].Method)
	assert.Equal(t, 5000, importer.requestSettings.inserted[0].Timeout)
	require.Len(t, importer.messages.inserted, 1)
	require.NotNil(t, importer.messages.inserted[0].Body)
	assert.JSONEq(t, `{"order":1}`, *importer.messages.inserted[0].Body)
	require.Len(t, importer.matchers.inserted, 1)
	assert.Equal(t, "status is 200", importer.matchers.inserted[0].Name)
}

// The failure behind a refused import is a bun or PostgreSQL message, which
// names constraints, tables and columns. The caller reads the result, so the
// detail belongs in the log instead.
func TestImportingATestCaseReportsAFailingSaveWithoutTheDatabaseMessage(t *testing.T) {
	f := newTestCasesFixture()
	f.testCases.insertErr = errors.New(`pq: duplicate key value violates unique constraint "test_cases_pkey"`)

	results, err := f.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, exportedTestCase(t, dao.TestCase{ID: uuid.New(), Name: "imported"}))})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultError, (*results)[0].Result)
	assert.Equal(t, "failed to save the test case", (*results)[0].Message)
	assert.NotContains(t, (*results)[0].Message, "constraint")
	assert.Contains(t, f.logs.String(), "test_cases_pkey", "the failure itself is logged")
}
