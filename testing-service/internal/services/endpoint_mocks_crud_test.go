package services

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"mime/multipart"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/matching"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

type endpointMocksFixture struct {
	service            EndpointMocksService
	logs               *bytes.Buffer
	runner             *fakeRunner
	endpointMocks      *fakeEndpointMocksRepository
	endpointReferences *fakeEndpointReferencesRepository
	responseSettings   *fakeResponseSettingsRepository
	messages           *fakeMessagesRepository
	headers            *fakeHeadersRepository
	matchers           *fakeMatchersRepository
	matcherParameters  *fakeMatcherParametersRepository
}

func newEndpointMocksFixture() *endpointMocksFixture {
	f := &endpointMocksFixture{
		logs:               &bytes.Buffer{},
		runner:             &fakeRunner{},
		endpointMocks:      &fakeEndpointMocksRepository{existing: map[uuid.UUID]*dao.EndpointMock{}},
		endpointReferences: &fakeEndpointReferencesRepository{},
		responseSettings:   &fakeResponseSettingsRepository{},
		messages:           &fakeMessagesRepository{},
		headers:            &fakeHeadersRepository{},
		matchers:           &fakeMatchersRepository{},
		matcherParameters:  &fakeMatcherParametersRepository{},
	}
	f.service = NewEndpointMocksService(slog.New(slog.NewTextHandler(f.logs, nil)), f.runner, dao.Repositories{
		EndpointMocks:      f.endpointMocks,
		EndpointReferences: f.endpointReferences,
		ResponseSettings:   f.responseSettings,
		Messages:           f.messages,
		Headers:            f.headers,
		Matchers:           f.matchers,
		MatcherParameters:  f.matcherParameters,
	})
	return f
}

// kindHeader is the header the mock below matches on. A matcher is validated on
// save, so a test fixture carries a matcher that can actually be built.
var kindHeader = "X-Kind"

func fullEndpointMock() *dao.EndpointMock {
	body := `{"ok":true}`
	return &dao.EndpointMock{
		Name:              "order service",
		Enabled:           true,
		EndpointReference: &dao.EndpointReference{ChainID: "chain-1", ElementID: "element-1"},
		ResponseSettings: &dao.ResponseSettings{
			Status: 201,
			Delay:  10,
			Message: &dao.Message{
				Body:    &body,
				Headers: []*dao.Header{{Name: "Content-Type", Value: "application/json"}, nil},
			},
		},
		RequestMatchers: []*dao.Matcher{{
			Name:       "kind is order",
			Enabled:    true,
			Type:       "equal",
			EntityType: matching.EntityTypeHeader,
			EntityName: &kindHeader,
			Parameters: []*dao.MatcherParameter{{Name: "value", Value: "order"}},
		}},
	}
}

func TestCreateLinksEveryChildToTheStoredEndpointMock(t *testing.T) {
	f := newEndpointMocksFixture()

	created, err := f.service.Create(context.Background(), fullEndpointMock())

	require.NoError(t, err)
	require.Len(t, f.endpointMocks.inserted, 1)
	id := f.endpointMocks.inserted[0].ID
	assert.Equal(t, id, created.ID)
	assert.Equal(t, 1, f.runner.txCalls)

	require.Len(t, f.endpointReferences.inserted, 1)
	assert.Equal(t, id, f.endpointReferences.inserted[0].EndpointMockID)

	require.Len(t, f.responseSettings.inserted, 1)
	assert.Equal(t, id, f.responseSettings.inserted[0].EndpointMockID)

	require.Len(t, f.messages.inserted, 1)
	assert.Equal(t, f.responseSettings.inserted[0].ID, f.messages.inserted[0].OwnerID)
	require.Len(t, f.headers.batches, 1)
	require.Len(t, f.headers.batches[0], 1, "a nil header is skipped")

	require.Len(t, f.matchers.inserted, 1)
	assert.Equal(t, id, f.matchers.inserted[0].OwnerID)
}

func TestCreateReportsAFailingEndpointMockInsert(t *testing.T) {
	failure := errors.New("constraint violated")
	f := newEndpointMocksFixture()
	f.endpointMocks.insertErr = failure

	created, err := f.service.Create(context.Background(), fullEndpointMock())

	require.ErrorIs(t, err, failure)
	assert.Nil(t, created)
}

func TestUpdateRejectsAnEndpointMockThatIsGone(t *testing.T) {
	f := newEndpointMocksFixture()

	id := uuid.New()

	updated, err := f.service.Update(context.Background(), &dao.EndpointMock{ID: id})

	// The sentinel is what the controller answers 404 to. Without it the caller
	// reads a 500 about this service over an id of its own.
	require.ErrorIs(t, err, ErrNotFound)
	assert.ErrorContains(t, err, id.String())
	assert.Nil(t, updated)
}

// response_settings.endpoint_mock_id is unique and not null with a foreign key,
// and it is json:"-", so settings added to a mock that had none used to be
// inserted under the zero UUID.
func TestUpdateStampsTheOwnerOnResponseSettingsAddedToAMockThatHadNone(t *testing.T) {
	f := newEndpointMocksFixture()
	id := uuid.New()
	f.endpointMocks.existing[id] = &dao.EndpointMock{ID: id, Name: "bare"}

	updated, err := f.service.Update(context.Background(), &dao.EndpointMock{
		ID:               id,
		ResponseSettings: &dao.ResponseSettings{Status: 200},
	})

	require.NoError(t, err)
	require.NotNil(t, updated.ResponseSettings)
	require.Len(t, f.responseSettings.inserted, 1)
	assert.Equal(t, id, f.responseSettings.inserted[0].EndpointMockID)
	assert.NotEqual(t, uuid.Nil, f.responseSettings.inserted[0].EndpointMockID)
	assert.Empty(t, f.responseSettings.deleted, "there was nothing to replace")
}

func TestUpdateReplacesTheStoredResponseSettings(t *testing.T) {
	f := newEndpointMocksFixture()
	id := uuid.New()
	settingsID := uuid.New()
	f.endpointMocks.existing[id] = &dao.EndpointMock{
		ID:               id,
		ResponseSettings: &dao.ResponseSettings{ID: settingsID, Status: 200},
	}

	_, err := f.service.Update(context.Background(), &dao.EndpointMock{
		ID:               id,
		ResponseSettings: &dao.ResponseSettings{Status: 500},
	})

	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{settingsID}, f.responseSettings.deleted)
	require.Len(t, f.responseSettings.inserted, 1)
	assert.Equal(t, settingsID, f.responseSettings.inserted[0].ID)
	assert.Equal(t, id, f.responseSettings.inserted[0].EndpointMockID)
	assert.Equal(t, 500, f.responseSettings.inserted[0].Status)
}

func TestUpdateDropsResponseSettingsTheRequestLeftOut(t *testing.T) {
	f := newEndpointMocksFixture()
	id := uuid.New()
	settingsID := uuid.New()
	f.endpointMocks.existing[id] = &dao.EndpointMock{
		ID:               id,
		ResponseSettings: &dao.ResponseSettings{ID: settingsID},
	}

	updated, err := f.service.Update(context.Background(), &dao.EndpointMock{ID: id})

	require.NoError(t, err)
	assert.Nil(t, updated.ResponseSettings)
	assert.Equal(t, []uuid.UUID{settingsID}, f.responseSettings.deleted)
	assert.Empty(t, f.responseSettings.inserted)
}

func TestUpdateStampsTheOwnerOnAnEndpointReferenceAddedToAMockThatHadNone(t *testing.T) {
	f := newEndpointMocksFixture()
	id := uuid.New()
	f.endpointMocks.existing[id] = &dao.EndpointMock{ID: id}

	_, err := f.service.Update(context.Background(), &dao.EndpointMock{
		ID:                id,
		EndpointReference: &dao.EndpointReference{ChainID: "chain-1", ElementID: "element-1"},
	})

	require.NoError(t, err)
	require.Len(t, f.endpointReferences.inserted, 1)
	assert.Equal(t, id, f.endpointReferences.inserted[0].EndpointMockID)
}

func TestUpdateUpdatesTheStoredEndpointReferenceInPlace(t *testing.T) {
	f := newEndpointMocksFixture()
	id := uuid.New()
	referenceID := uuid.New()
	f.endpointMocks.existing[id] = &dao.EndpointMock{
		ID:                id,
		EndpointReference: &dao.EndpointReference{ID: referenceID, ChainID: "old"},
	}

	_, err := f.service.Update(context.Background(), &dao.EndpointMock{
		ID:                id,
		EndpointReference: &dao.EndpointReference{ChainID: "new"},
	})

	require.NoError(t, err)
	assert.Empty(t, f.endpointReferences.inserted)
	require.Len(t, f.endpointReferences.updated, 1)
	assert.Equal(t, referenceID, f.endpointReferences.updated[0].ID)
	assert.Equal(t, id, f.endpointReferences.updated[0].EndpointMockID)
	assert.Equal(t, "new", f.endpointReferences.updated[0].ChainID)
}

// existsMatcher is the simplest matcher that can be built: it takes no
// parameters and passes whenever the body is there.
func existsMatcher(name string) *dao.Matcher {
	return &dao.Matcher{ID: uuid.New(), Name: name, Type: "exist", EntityType: matching.EntityTypeBody}
}

// The matchers decide which mock answers a call, so an update has to drop the
// old set before it stores the new one under the same owner.
func TestUpdateReplacesTheRequestMatchers(t *testing.T) {
	f := newEndpointMocksFixture()
	id := uuid.New()
	f.endpointMocks.existing[id] = &dao.EndpointMock{
		ID:              id,
		RequestMatchers: []*dao.Matcher{existsMatcher("old")},
	}

	updated, err := f.service.Update(context.Background(), &dao.EndpointMock{
		ID:              id,
		RequestMatchers: []*dao.Matcher{existsMatcher("first"), existsMatcher("second")},
	})

	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{id}, f.matchers.deletedByOwner)
	require.Len(t, f.matchers.inserted, 2)
	assert.Equal(t, "first", f.matchers.inserted[0].Name)
	assert.Equal(t, id, f.matchers.inserted[0].OwnerID)
	assert.Equal(t, id, f.matchers.inserted[1].OwnerID)
	assert.Len(t, updated.RequestMatchers, 2)
}

func TestDeleteAnEndpointMockTakesItsMatchersWithIt(t *testing.T) {
	f := newEndpointMocksFixture()
	id := uuid.New()

	require.NoError(t, f.service.Delete(context.Background(), id))

	assert.Equal(t, []uuid.UUID{id}, f.endpointMocks.deleted)
	assert.Equal(t, []uuid.UUID{id}, f.matchers.deletedByOwner)
}

func TestBulkDeleteEndpointMocksTakesTheirMatchersWithThem(t *testing.T) {
	f := newEndpointMocksFixture()
	ids := []uuid.UUID{uuid.New(), uuid.New()}

	require.NoError(t, f.service.BulkDelete(context.Background(), &ids))

	require.Len(t, f.matchers.deletedByOwners, 1)
	assert.Equal(t, ids, f.matchers.deletedByOwners[0])
}

func exportedEndpointMock(t *testing.T, endpointMock dao.EndpointMock) model.ExportedEntity {
	t.Helper()
	data, err := json.Marshal(endpointMock)
	require.NoError(t, err)
	return model.ExportedEntity{
		Version: 1,
		Type:    model.ExportedTypeEndpointMock,
		ID:      endpointMock.ID,
		Name:    endpointMock.Name,
		Data:    data,
	}
}

func TestExportWritesOneEntityFilePerEndpointMock(t *testing.T) {
	f := newEndpointMocksFixture()
	first := dao.EndpointMock{ID: uuid.New(), Name: "first", Enabled: true}
	f.endpointMocks.mocks = []dao.EndpointMock{first}

	data, err := f.service.Export(context.Background(), &[]uuid.UUID{first.ID})

	require.NoError(t, err)
	entities := exportedArchive(t, *data)
	require.Len(t, entities, 1)
	assert.Equal(t, model.ExportedTypeEndpointMock, entities[0].Type)
	assert.Equal(t, first.ID, entities[0].ID)
	assert.Positive(t, entities[0].Version)
}

// An export of nothing is an empty archive. The same request used to dump every
// mock in the installation, because an empty id list narrowed the query by
// nothing at all.
func TestExportWritesAnEmptyArchiveForAnEmptyEndpointMockSelection(t *testing.T) {
	f := newEndpointMocksFixture()
	f.endpointMocks.mocks = []dao.EndpointMock{{ID: uuid.New(), Name: "kept"}}

	data, err := f.service.Export(context.Background(), &[]uuid.UUID{})

	require.NoError(t, err)
	assert.Empty(t, exportedArchive(t, *data))
}

func TestImportCreatesAnEndpointMockTheDatabaseDoesNotHaveYet(t *testing.T) {
	f := newEndpointMocksFixture()
	endpointMock := dao.EndpointMock{ID: uuid.New(), Name: "imported"}

	results, err := f.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, exportedEndpointMock(t, endpointMock))})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultCreated, (*results)[0].Result)
	require.Len(t, f.endpointMocks.inserted, 1)
	assert.Equal(t, endpointMock.ID, f.endpointMocks.inserted[0].ID)
}

func TestImportUpdatesAnEndpointMockTheDatabaseAlreadyHas(t *testing.T) {
	f := newEndpointMocksFixture()
	id := uuid.New()
	f.endpointMocks.existing[id] = &dao.EndpointMock{ID: id, Name: "old"}

	results, err := f.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, exportedEndpointMock(t, dao.EndpointMock{ID: id, Name: "new"}))})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultUpdated, (*results)[0].Result)
	require.Len(t, f.endpointMocks.updated, 1)
	assert.Equal(t, "new", f.endpointMocks.updated[0].Name)
}

func TestImportRejectsAnEntityThatIsNotAnEndpointMock(t *testing.T) {
	f := newEndpointMocksFixture()
	entity := exportedEndpointMock(t, dao.EndpointMock{ID: uuid.New(), Name: "a test case"})
	entity.Type = model.ExportedTypeTestCase

	results, err := f.service.Import(context.Background(), []*multipart.FileHeader{archiveOf(t, entity)})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultError, (*results)[0].Result)
	assert.Contains(t, (*results)[0].Message, "wrong entity type")
	assert.Empty(t, f.endpointMocks.inserted)
}

func TestAnEndpointMockSurvivesAnExportAndImportRoundTrip(t *testing.T) {
	exporter := newEndpointMocksFixture()
	endpointMock := *fullEndpointMock()
	endpointMock.ID = uuid.New()
	exporter.endpointMocks.mocks = []dao.EndpointMock{endpointMock}

	data, err := exporter.service.Export(context.Background(), &[]uuid.UUID{endpointMock.ID})
	require.NoError(t, err)
	entities := exportedArchive(t, *data)
	require.Len(t, entities, 1)

	importer := newEndpointMocksFixture()
	results, err := importer.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, entities[0])})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultCreated, (*results)[0].Result)
	require.Len(t, importer.endpointMocks.inserted, 1)
	assert.Equal(t, endpointMock.ID, importer.endpointMocks.inserted[0].ID)
	require.Len(t, importer.endpointReferences.inserted, 1)
	assert.Equal(t, "chain-1", importer.endpointReferences.inserted[0].ChainID)
	require.Len(t, importer.responseSettings.inserted, 1)
	assert.Equal(t, 201, importer.responseSettings.inserted[0].Status)
	assert.Equal(t, 10, importer.responseSettings.inserted[0].Delay)
	require.Len(t, importer.matchers.inserted, 1)
	assert.Equal(t, "kind is order", importer.matchers.inserted[0].Name)
}

// The failure behind a refused import is a bun or PostgreSQL message, which
// names constraints, tables and columns. The caller reads the result, so the
// detail belongs in the log instead.
func TestImportingAnEndpointMockReportsAFailingSaveWithoutTheDatabaseMessage(t *testing.T) {
	f := newEndpointMocksFixture()
	f.endpointMocks.insertErr = errors.New(`pq: duplicate key value violates unique constraint "endpoint_mocks_pkey"`)

	results, err := f.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, exportedEndpointMock(t, dao.EndpointMock{ID: uuid.New(), Name: "mock"}))})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultError, (*results)[0].Result)
	assert.Equal(t, "failed to save the endpoint mock", (*results)[0].Message)
	assert.NotContains(t, (*results)[0].Message, "constraint")
	assert.Contains(t, f.logs.String(), "endpoint_mocks_pkey", "the failure itself is logged")
}

// mockWithResponse builds the smallest mock that carries the given response
// settings, which is what the validation reads.
func mockWithResponse(responseSettings *dao.ResponseSettings) *dao.EndpointMock {
	return &dao.EndpointMock{Name: "mock", ResponseSettings: responseSettings}
}

// The status reaches the status line and the headers reach the header lines
// verbatim, so a value that cannot be written as one is refused on the way in.
func refusedResponses() map[string]*dao.ResponseSettings {
	return map[string]*dao.ResponseSettings{
		"status below the range": {Status: -1},
		"status above the range": {Status: 70000},
		"line break in a header value": {Status: 200, Message: &dao.Message{
			Headers: []*dao.Header{{Name: "X-Mocked", Value: "yes\r\nX-Injected: 1"}},
		}},
		"line break in a header name": {Status: 200, Message: &dao.Message{
			Headers: []*dao.Header{{Name: "X-Mocked\nX-Injected", Value: "1"}},
		}},
		"empty header name": {Status: 200, Message: &dao.Message{
			Headers: []*dao.Header{{Name: "", Value: "yes"}},
		}},
		"space in a header name": {Status: 200, Message: &dao.Message{
			Headers: []*dao.Header{{Name: "X Mocked", Value: "yes"}},
		}},
		"colon in a header name": {Status: 200, Message: &dao.Message{
			Headers: []*dao.Header{{Name: "X-Mocked: yes", Value: "1"}},
		}},
		"null byte in a header name": {Status: 200, Message: &dao.Message{
			Headers: []*dao.Header{{Name: "X-Mocked\x00", Value: "yes"}},
		}},
		"null byte in a header value": {Status: 200, Message: &dao.Message{
			Headers: []*dao.Header{{Name: "X-Mocked", Value: "yes\x00no"}},
		}},
		"vertical tab in a header value": {Status: 200, Message: &dao.Message{
			Headers: []*dao.Header{{Name: "X-Mocked", Value: "yes\vno"}},
		}},
		"delete character in a header value": {Status: 200, Message: &dao.Message{
			Headers: []*dao.Header{{Name: "X-Mocked", Value: "yes\x7fno"}},
		}},
	}
}

// The characters a field name and a field value are allowed to carry are wider
// than the alphanumerics, and refusing a legitimate header would break mocks
// that already answer with one.
func TestCreateAcceptsAResponseHeaderTheWireCanCarry(t *testing.T) {
	accepted := map[string]*dao.Header{
		"token specials in the name": {Name: "X-Mocked_1.2~3", Value: "yes"},
		"tab in the value":           {Name: "X-Mocked", Value: "yes\tno"},
		"spaces in the value":        {Name: "X-Mocked", Value: "one two three"},
		"non-ascii in the value":     {Name: "X-Mocked", Value: "ré"},
		"empty value":                {Name: "X-Mocked", Value: ""},
	}
	for name, header := range accepted {
		t.Run(name, func(t *testing.T) {
			f := newEndpointMocksFixture()

			created, err := f.service.Create(context.Background(), mockWithResponse(&dao.ResponseSettings{
				Status: 200, Message: &dao.Message{Headers: []*dao.Header{header}},
			}))

			require.NoError(t, err)
			assert.NotNil(t, created)
		})
	}
}

func TestCreateRefusesAResponseThatCouldNotBeWrittenOut(t *testing.T) {
	for name, responseSettings := range refusedResponses() {
		t.Run(name, func(t *testing.T) {
			f := newEndpointMocksFixture()

			created, err := f.service.Create(context.Background(), mockWithResponse(responseSettings))

			require.ErrorIs(t, err, ErrInvalidRequest)
			assert.Nil(t, created)
			assert.Empty(t, f.endpointMocks.inserted, "nothing is stored for a refused mock")
		})
	}
}

func TestUpdateRefusesAResponseThatCouldNotBeWrittenOut(t *testing.T) {
	for name, responseSettings := range refusedResponses() {
		t.Run(name, func(t *testing.T) {
			f := newEndpointMocksFixture()
			id := uuid.New()
			f.endpointMocks.existing[id] = &dao.EndpointMock{ID: id, Name: "old"}
			endpointMock := mockWithResponse(responseSettings)
			endpointMock.ID = id

			updated, err := f.service.Update(context.Background(), endpointMock)

			require.ErrorIs(t, err, ErrInvalidRequest)
			assert.Nil(t, updated)
			assert.Empty(t, f.endpointMocks.updated, "nothing is stored for a refused mock")
		})
	}
}

// Import reaches the same validation Create does, so an archive cannot store a
// response the service could not write out.
func TestImportRefusesAResponseThatCouldNotBeWrittenOut(t *testing.T) {
	for name, responseSettings := range refusedResponses() {
		t.Run(name, func(t *testing.T) {
			f := newEndpointMocksFixture()
			endpointMock := mockWithResponse(responseSettings)
			endpointMock.ID = uuid.New()

			results, err := f.service.Import(context.Background(),
				[]*multipart.FileHeader{archiveOf(t, exportedEndpointMock(t, *endpointMock))})

			require.NoError(t, err)
			require.Len(t, *results, 1)
			assert.Equal(t, model.ImportResultError, (*results)[0].Result)
			assert.Empty(t, f.endpointMocks.inserted, "nothing is stored for a refused mock")
		})
	}
}

// mockWithMatcher builds the smallest mock that carries the given matcher.
func mockWithMatcher(matcher *dao.Matcher) *dao.EndpointMock {
	return &dao.EndpointMock{Name: "mock", RequestMatchers: []*dao.Matcher{matcher}}
}

// Call skips a matcher it cannot build, so a mock carrying one never answers.
// The mistake is refused where it is made instead: on the request that stores it.
func refusedMatchers() map[string]*dao.Matcher {
	value := "X-Kind"
	blank := "  "
	spaced := "X Kind"
	braced := "order}Id"
	slashed := "order/Id"
	return map[string]*dao.Matcher{
		// A name outside the grammar of its entity type shadows the same way a
		// missing one does: no header is ever found under `X Kind`, and no path
		// template placeholder can spell `order}Id` or `order/Id`.
		"header matcher whose entity name is not an HTTP field name": {
			Name: "m", Type: "empty", EntityType: matching.EntityTypeHeader, EntityName: &spaced,
		},
		"path parameter matcher whose entity name carries a closing brace": {
			Name: "m", Type: "empty", EntityType: matching.EntityTypePathParameter, EntityName: &braced,
		},
		"path parameter matcher whose entity name spans two segments": {
			Name: "m", Type: "empty", EntityType: matching.EntityTypePathParameter, EntityName: &slashed,
		},
		// A named entity type without a name reads nothing out of every exchange,
		// so an `empty` matcher over it holds for every call and the mock carrying
		// it shadows the specific ones it outranks on creation time.
		"header matcher without an entity name": {
			Name: "m", Type: "empty", EntityType: matching.EntityTypeHeader,
		},
		"query parameter matcher without an entity name": {
			Name: "m", Type: "empty", EntityType: matching.EntityTypeQueryParameter,
		},
		"path parameter matcher with a blank entity name": {
			Name: "m", Type: "empty", EntityType: matching.EntityTypePathParameter, EntityName: &blank,
		},
		"unknown matcher type": {Name: "m", Type: "sounds_like", EntityType: matching.EntityTypeBody},
		"unknown entity type":  {Name: "m", Type: "exist", EntityType: "cookie"},
		"pattern that is not a regular expression": {
			Name: "m", Type: "match", EntityType: matching.EntityTypeBody,
			Parameters: []*dao.MatcherParameter{{Name: "pattern", Value: "("}},
		},
		"missing the parameter the type requires": {
			Name: "m", Type: "equal", EntityType: matching.EntityTypeHeader, EntityName: &value,
		},
		"sample that is not JSON": {
			Name: "m", Type: "match_json", EntityType: matching.EntityTypeBody,
			Parameters: []*dao.MatcherParameter{{Name: "sample", Value: "{"}},
		},
		// A matcher one toggle away from being evaluated is checked like the rest.
		"disabled matcher that cannot be built": {
			Name: "m", Type: "match", EntityType: matching.EntityTypeBody, Enabled: false,
			Parameters: []*dao.MatcherParameter{{Name: "pattern", Value: "["}},
		},
	}
}

func TestCreateRefusesAMatcherThatCannotBeBuilt(t *testing.T) {
	for name, matcher := range refusedMatchers() {
		t.Run(name, func(t *testing.T) {
			f := newEndpointMocksFixture()

			created, err := f.service.Create(context.Background(), mockWithMatcher(matcher))

			require.ErrorIs(t, err, ErrInvalidRequest)
			assert.Nil(t, created)
			assert.Empty(t, f.endpointMocks.inserted, "nothing is stored for a refused mock")
		})
	}
}

func TestUpdateRefusesAMatcherThatCannotBeBuilt(t *testing.T) {
	for name, matcher := range refusedMatchers() {
		t.Run(name, func(t *testing.T) {
			f := newEndpointMocksFixture()
			id := uuid.New()
			f.endpointMocks.existing[id] = &dao.EndpointMock{ID: id, Name: "old"}
			endpointMock := mockWithMatcher(matcher)
			endpointMock.ID = id

			updated, err := f.service.Update(context.Background(), endpointMock)

			require.ErrorIs(t, err, ErrInvalidRequest)
			assert.Nil(t, updated)
			assert.Empty(t, f.endpointMocks.updated, "nothing is stored for a refused mock")
		})
	}
}

// The refusal is about the imported file, so the importer reads what to fix
// rather than the generic message a failing save reports.
func TestImportReportsARefusedResponseWithItsReason(t *testing.T) {
	f := newEndpointMocksFixture()
	endpointMock := *mockWithResponse(&dao.ResponseSettings{Status: 70000})
	endpointMock.ID = uuid.New()

	results, err := f.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, exportedEndpointMock(t, endpointMock))})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultError, (*results)[0].Result)
	assert.Contains(t, (*results)[0].Message, "70000")
	assert.Empty(t, f.endpointMocks.inserted)
}

func TestImportReportsARefusedMatcherWithItsReason(t *testing.T) {
	f := newEndpointMocksFixture()
	endpointMock := *mockWithMatcher(&dao.Matcher{
		Name: "m", Type: "empty", EntityType: matching.EntityTypeHeader,
	})
	endpointMock.ID = uuid.New()

	results, err := f.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, exportedEndpointMock(t, endpointMock))})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultError, (*results)[0].Result)
	assert.Contains(t, (*results)[0].Message, matching.EntityTypeHeader)
	assert.Empty(t, f.endpointMocks.inserted)
}

// The body and the status are the message itself, so a matcher over either one
// carries no entity name and the name check may not turn it into a rejection.
func TestCreateAcceptsAMatcherOverAnEntityThatTakesNoName(t *testing.T) {
	for _, entityType := range []string{matching.EntityTypeBody, matching.EntityTypeStatus} {
		t.Run(entityType, func(t *testing.T) {
			f := newEndpointMocksFixture()

			created, err := f.service.Create(context.Background(),
				mockWithMatcher(&dao.Matcher{Name: "m", Type: "empty", EntityType: entityType}))

			require.NoError(t, err)
			require.NotNil(t, created)
			assert.Len(t, f.endpointMocks.inserted, 1)
		})
	}
}

// A mock that names no status is what the API accepts for a response that
// answers 200, and the new range may not turn it into a rejection.
func TestCreateAcceptsAMockThatNamesNoStatus(t *testing.T) {
	f := newEndpointMocksFixture()

	created, err := f.service.Create(context.Background(), mockWithResponse(&dao.ResponseSettings{}))

	require.NoError(t, err)
	require.NotNil(t, created)
	assert.Len(t, f.endpointMocks.inserted, 1)
}

// storedMock is the row an update is measured against: the same mock, already in
// the database under the given id.
func storedMock(id uuid.UUID, endpointMock *dao.EndpointMock) *dao.EndpointMock {
	stored := *endpointMock
	stored.ID = id
	return &stored
}

// A row saved before these rules existed still reads back, and until now it could
// not be saved again: an update validates the whole entity, so the legacy value
// blocked every other edit. The update now keeps that value and goes through, and
// the log names the entity, the offending element and the rule.
func TestUpdateKeepsAResponseTheStoredMockAlreadyCarries(t *testing.T) {
	for name, responseSettings := range refusedResponses() {
		t.Run(name, func(t *testing.T) {
			f := newEndpointMocksFixture()
			id := uuid.New()
			f.endpointMocks.existing[id] = storedMock(id, mockWithResponse(responseSettings))
			endpointMock := mockWithResponse(responseSettings)
			endpointMock.ID = id
			endpointMock.Name = "renamed while the legacy response stays"

			updated, err := f.service.Update(context.Background(), endpointMock)

			require.NoError(t, err)
			require.NotNil(t, updated)
			require.Len(t, f.endpointMocks.updated, 1)
			assert.Equal(t, "renamed while the legacy response stays", f.endpointMocks.updated[0].Name)
			assert.Contains(t, f.logs.String(), "endpoint mock")
			assert.Contains(t, f.logs.String(), id.String())
		})
	}
}

func TestUpdateKeepsAMatcherTheStoredMockAlreadyCarries(t *testing.T) {
	for name, matcher := range refusedMatchers() {
		t.Run(name, func(t *testing.T) {
			f := newEndpointMocksFixture()
			id := uuid.New()
			legacy := *matcher
			f.endpointMocks.existing[id] = storedMock(id, mockWithMatcher(&legacy))
			endpointMock := mockWithMatcher(matcher)
			endpointMock.ID = id

			updated, err := f.service.Update(context.Background(), endpointMock)

			require.NoError(t, err)
			require.NotNil(t, updated)
			assert.Len(t, f.endpointMocks.updated, 1)
			assert.Contains(t, f.logs.String(), "endpoint mock")
		})
	}
}

// Leniency covers the value that is already stored, and nothing else. Replacing
// one refused value with another is new input, whichever rule it breaks.
func TestUpdateRefusesADifferentBadValueThanTheStoredOne(t *testing.T) {
	spaced, otherSpaced := "X Kind", "Y Kind"
	cases := map[string]struct{ stored, incoming *dao.EndpointMock }{
		"another status outside the range": {
			stored:   mockWithResponse(&dao.ResponseSettings{Status: 70000}),
			incoming: mockWithResponse(&dao.ResponseSettings{Status: 80000}),
		},
		"another header name that is not a field name": {
			stored: mockWithResponse(&dao.ResponseSettings{Status: 200, Message: &dao.Message{
				Headers: []*dao.Header{{Name: "X Mocked", Value: "yes"}},
			}}),
			incoming: mockWithResponse(&dao.ResponseSettings{Status: 200, Message: &dao.Message{
				Headers: []*dao.Header{{Name: "Y Mocked", Value: "yes"}},
			}}),
		},
		"another control character in the same header value": {
			stored: mockWithResponse(&dao.ResponseSettings{Status: 200, Message: &dao.Message{
				Headers: []*dao.Header{{Name: "X-Mocked", Value: "yes\x00no"}},
			}}),
			incoming: mockWithResponse(&dao.ResponseSettings{Status: 200, Message: &dao.Message{
				Headers: []*dao.Header{{Name: "X-Mocked", Value: "yes\vno"}},
			}}),
		},
		"another entity name outside the grammar": {
			stored: mockWithMatcher(&dao.Matcher{
				Name: "m", Type: "empty", EntityType: matching.EntityTypeHeader, EntityName: &spaced,
			}),
			incoming: mockWithMatcher(&dao.Matcher{
				Name: "m", Type: "empty", EntityType: matching.EntityTypeHeader, EntityName: &otherSpaced,
			}),
		},
		"another pattern that is not a regular expression": {
			stored: mockWithMatcher(&dao.Matcher{
				Name: "m", Type: "match", EntityType: matching.EntityTypeBody,
				Parameters: []*dao.MatcherParameter{{Name: "pattern", Value: "("}},
			}),
			incoming: mockWithMatcher(&dao.Matcher{
				Name: "m", Type: "match", EntityType: matching.EntityTypeBody,
				Parameters: []*dao.MatcherParameter{{Name: "pattern", Value: "["}},
			}),
		},
	}
	for name, testCase := range cases {
		t.Run(name, func(t *testing.T) {
			f := newEndpointMocksFixture()
			id := uuid.New()
			f.endpointMocks.existing[id] = storedMock(id, testCase.stored)
			testCase.incoming.ID = id

			updated, err := f.service.Update(context.Background(), testCase.incoming)

			require.ErrorIs(t, err, ErrInvalidRequest)
			assert.Nil(t, updated)
			assert.Empty(t, f.endpointMocks.updated, "nothing is stored for a refused mock")
		})
	}
}

// legacyMock is a mock breaking three rules at once: the row the vendor has to be
// able to edit, and the one that must not turn into a row accepting anything.
func legacyMock(id uuid.UUID, status int) *dao.EndpointMock {
	spaced := "X Kind"
	return &dao.EndpointMock{
		ID:   id,
		Name: "legacy",
		RequestMatchers: []*dao.Matcher{{
			Name: "m", Type: "empty", EntityType: matching.EntityTypeHeader, EntityName: &spaced,
		}},
		ResponseSettings: &dao.ResponseSettings{Status: status, Message: &dao.Message{
			Headers: []*dao.Header{{Name: "X Mocked", Value: "yes"}},
		}},
	}
}

func TestUpdateKeepsEveryViolationTheStoredMockCarries(t *testing.T) {
	f := newEndpointMocksFixture()
	id := uuid.New()
	f.endpointMocks.existing[id] = legacyMock(id, 70000)

	updated, err := f.service.Update(context.Background(), legacyMock(id, 70000))

	require.NoError(t, err)
	require.NotNil(t, updated)
	assert.Len(t, f.endpointMocks.updated, 1)
}

// A row carrying several legacy values is not a row that accepts any value: each
// one is tolerated on its own.
func TestUpdateRefusesANewViolationBesideTheStoredOnes(t *testing.T) {
	f := newEndpointMocksFixture()
	id := uuid.New()
	f.endpointMocks.existing[id] = legacyMock(id, 70000)

	// The matcher and the header stay as they were stored; only the status moves
	// to a value the stored row never carried.
	updated, err := f.service.Update(context.Background(), legacyMock(id, 80000))

	require.ErrorIs(t, err, ErrInvalidRequest)
	assert.Nil(t, updated)
	assert.Contains(t, err.Error(), "80000")
	assert.Empty(t, f.endpointMocks.updated)
}

// The vendor moves its data with the importer, so an archive entry that updates
// an existing mock is as lenient as the update endpoint, while one that creates a
// mock stays as strict as the create endpoint.
func TestImportKeepsAViolationTheStoredMockAlreadyCarries(t *testing.T) {
	f := newEndpointMocksFixture()
	id := uuid.New()
	endpointMock := *mockWithResponse(&dao.ResponseSettings{Status: 70000})
	endpointMock.ID = id
	f.endpointMocks.existing[id] = storedMock(id, mockWithResponse(&dao.ResponseSettings{Status: 70000}))

	results, err := f.service.Import(context.Background(),
		[]*multipart.FileHeader{archiveOf(t, exportedEndpointMock(t, endpointMock))})

	require.NoError(t, err)
	require.Len(t, *results, 1)
	assert.Equal(t, model.ImportResultUpdated, (*results)[0].Result)
	assert.Len(t, f.endpointMocks.updated, 1)
}
