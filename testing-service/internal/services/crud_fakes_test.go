package services

import (
	"context"
	"io"
	"log/slog"
	"slices"

	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// discardLogger keeps what a service logs out of the test output.
func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// The fakes below stand in for the repositories. Every one of
// them keeps what it was handed, so a test can assert on the owner ids the
// services stamp — which is what the foreign keys and the unique constraints on
// those columns turn into at runtime.

type fakeTestCasesRepository struct {
	dao.TestCasesRepository

	existing    map[uuid.UUID]*dao.TestCaseView
	inserted    []dao.TestCase
	updated     []dao.TestCase
	deleted     []uuid.UUID
	bulkDeletes [][]uuid.UUID
	views       []dao.TestCaseView
	insertErr   error
	updateErr   error
	findErr     error
}

// selectsId applies the id selection the way dao.AddSpecification does: no list
// at all puts no restriction on the query, a list selects what it names, and a
// list that is present but empty selects nothing. The stubs honor it because a
// stub that ignored the selection is what let an export of nothing dump the
// whole catalog unnoticed.
func selectsId(specification *model.SelectionSpecification, id uuid.UUID) bool {
	if specification == nil || specification.Ids == nil {
		return true
	}
	return slices.Contains(*specification.Ids, id)
}

func (r *fakeTestCasesRepository) FindAll(
	_ context.Context,
	specification *model.SelectionSpecification,
	_ model.SortOptions,
	_ *model.PaginationOptions,
	_ bool,
) (*[]dao.TestCaseView, error) {
	if r.findErr != nil {
		return nil, r.findErr
	}
	views := make([]dao.TestCaseView, 0, len(r.views))
	for _, view := range r.views {
		if selectsId(specification, view.ID) {
			views = append(views, view)
		}
	}
	return &views, nil
}

func (r *fakeTestCasesRepository) FindById(_ context.Context, id uuid.UUID, _ bool) (*dao.TestCaseView, error) {
	if r.findErr != nil {
		return nil, r.findErr
	}
	return r.existing[id], nil
}

func (r *fakeTestCasesRepository) Exists(_ context.Context, id uuid.UUID) (bool, error) {
	_, ok := r.existing[id]
	return ok, nil
}

func (r *fakeTestCasesRepository) Insert(_ context.Context, testCase *dao.TestCase) (*dao.TestCase, error) {
	if r.insertErr != nil {
		return nil, r.insertErr
	}
	stored := *testCase
	if stored.ID == uuid.Nil {
		stored.ID = uuid.New()
	}
	r.inserted = append(r.inserted, stored)
	return &stored, nil
}

func (r *fakeTestCasesRepository) Update(_ context.Context, testCase *dao.TestCase) error {
	if r.updateErr != nil {
		return r.updateErr
	}
	r.updated = append(r.updated, *testCase)
	return nil
}

func (r *fakeTestCasesRepository) Delete(_ context.Context, id uuid.UUID) error {
	r.deleted = append(r.deleted, id)
	return nil
}

func (r *fakeTestCasesRepository) BulkDelete(_ context.Context, ids *[]uuid.UUID) error {
	r.bulkDeletes = append(r.bulkDeletes, *ids)
	return nil
}

type fakeEndpointMocksRepository struct {
	dao.EndpointMocksRepository

	existing    map[uuid.UUID]*dao.EndpointMock
	inserted    []dao.EndpointMock
	updated     []dao.EndpointMock
	deleted     []uuid.UUID
	bulkDeletes [][]uuid.UUID
	mocks       []dao.EndpointMock
	insertErr   error
	findErr     error
	// lastFilters keeps what the Call path narrowed the listing by.
	lastFilters []model.Filter
}

func (r *fakeEndpointMocksRepository) BulkDelete(_ context.Context, ids *[]uuid.UUID) error {
	r.bulkDeletes = append(r.bulkDeletes, *ids)
	return nil
}

func (r *fakeEndpointMocksRepository) FindAll(
	_ context.Context,
	specification *model.SelectionSpecification,
	_ model.SortOptions,
	_ *model.PaginationOptions,
	_ bool,
) (*[]dao.EndpointMock, error) {
	if r.findErr != nil {
		return nil, r.findErr
	}
	if specification != nil && specification.Filters != nil {
		r.lastFilters = *specification.Filters
	}
	mocks := make([]dao.EndpointMock, 0, len(r.mocks))
	for _, mock := range r.mocks {
		if selectsId(specification, mock.ID) {
			mocks = append(mocks, mock)
		}
	}
	return &mocks, nil
}

func (r *fakeEndpointMocksRepository) FindById(_ context.Context, id uuid.UUID, _ bool) (*dao.EndpointMock, error) {
	if r.findErr != nil {
		return nil, r.findErr
	}
	return r.existing[id], nil
}

func (r *fakeEndpointMocksRepository) Exists(_ context.Context, id uuid.UUID) (bool, error) {
	_, ok := r.existing[id]
	return ok, nil
}

func (r *fakeEndpointMocksRepository) Insert(_ context.Context, endpointMock *dao.EndpointMock) (*dao.EndpointMock, error) {
	if r.insertErr != nil {
		return nil, r.insertErr
	}
	stored := *endpointMock
	if stored.ID == uuid.Nil {
		stored.ID = uuid.New()
	}
	r.inserted = append(r.inserted, stored)
	return &stored, nil
}

func (r *fakeEndpointMocksRepository) Update(_ context.Context, endpointMock *dao.EndpointMock) error {
	r.updated = append(r.updated, *endpointMock)
	return nil
}

func (r *fakeEndpointMocksRepository) Delete(_ context.Context, id uuid.UUID) error {
	r.deleted = append(r.deleted, id)
	return nil
}

type fakeTriggerReferencesRepository struct {
	inserted []dao.TriggerReference
	updated  []dao.TriggerReference
	deleted  []uuid.UUID
}

func (r *fakeTriggerReferencesRepository) Insert(
	_ context.Context,
	triggerReference *dao.TriggerReference,
) (*dao.TriggerReference, error) {
	stored := *triggerReference
	if stored.ID == uuid.Nil {
		stored.ID = uuid.New()
	}
	r.inserted = append(r.inserted, stored)
	return &stored, nil
}

func (r *fakeTriggerReferencesRepository) Update(_ context.Context, triggerReference *dao.TriggerReference) error {
	r.updated = append(r.updated, *triggerReference)
	return nil
}

func (r *fakeTriggerReferencesRepository) Delete(_ context.Context, id uuid.UUID) error {
	r.deleted = append(r.deleted, id)
	return nil
}

type fakeEndpointReferencesRepository struct {
	inserted []dao.EndpointReference
	updated  []dao.EndpointReference
	deleted  []uuid.UUID
}

func (r *fakeEndpointReferencesRepository) Insert(
	_ context.Context,
	endpointReference *dao.EndpointReference,
) (*dao.EndpointReference, error) {
	stored := *endpointReference
	if stored.ID == uuid.Nil {
		stored.ID = uuid.New()
	}
	r.inserted = append(r.inserted, stored)
	return &stored, nil
}

func (r *fakeEndpointReferencesRepository) Update(_ context.Context, endpointReference *dao.EndpointReference) error {
	r.updated = append(r.updated, *endpointReference)
	return nil
}

func (r *fakeEndpointReferencesRepository) Delete(_ context.Context, id uuid.UUID) error {
	r.deleted = append(r.deleted, id)
	return nil
}

type fakeRequestSettingsRepository struct {
	inserted []dao.RequestSettings
	deleted  []uuid.UUID
}

func (r *fakeRequestSettingsRepository) Insert(
	_ context.Context,
	requestSettings *dao.RequestSettings,
) (*dao.RequestSettings, error) {
	stored := *requestSettings
	if stored.ID == uuid.Nil {
		stored.ID = uuid.New()
	}
	r.inserted = append(r.inserted, stored)
	return &stored, nil
}

func (r *fakeRequestSettingsRepository) Delete(_ context.Context, id uuid.UUID) error {
	r.deleted = append(r.deleted, id)
	return nil
}

type fakeResponseSettingsRepository struct {
	inserted []dao.ResponseSettings
	deleted  []uuid.UUID
}

func (r *fakeResponseSettingsRepository) Insert(
	_ context.Context,
	responseSettings *dao.ResponseSettings,
) (*dao.ResponseSettings, error) {
	stored := *responseSettings
	if stored.ID == uuid.Nil {
		stored.ID = uuid.New()
	}
	r.inserted = append(r.inserted, stored)
	return &stored, nil
}

func (r *fakeResponseSettingsRepository) Delete(_ context.Context, id uuid.UUID) error {
	r.deleted = append(r.deleted, id)
	return nil
}

type fakeMessagesRepository struct {
	inserted []dao.Message
}

func (r *fakeMessagesRepository) Insert(_ context.Context, message *dao.Message) (*dao.Message, error) {
	stored := *message
	if stored.ID == uuid.Nil {
		stored.ID = uuid.New()
	}
	r.inserted = append(r.inserted, stored)
	return &stored, nil
}

type fakeHeadersRepository struct {
	batches [][]dao.Header
}

func (r *fakeHeadersRepository) BulkInsert(_ context.Context, headers *[]dao.Header) error {
	r.batches = append(r.batches, *headers)
	return nil
}

type fakePathParametersRepository struct {
	batches [][]dao.PathParameter
}

func (r *fakePathParametersRepository) BulkInsert(_ context.Context, params *[]dao.PathParameter) error {
	r.batches = append(r.batches, *params)
	return nil
}

type fakeQueryParametersRepository struct {
	batches [][]dao.QueryParameter
}

func (r *fakeQueryParametersRepository) BulkInsert(_ context.Context, params *[]dao.QueryParameter) error {
	r.batches = append(r.batches, *params)
	return nil
}

// fakeMatchersRepository records the matchers it stored and the owners whose
// matchers were dropped, which is how a test sees the replace-on-update.
type fakeMatchersRepository struct {
	dao.MatchersRepository

	inserted        []dao.Matcher
	deletedByOwner  []uuid.UUID
	deletedByOwners [][]uuid.UUID
	insertErr       error
}

func (r *fakeMatchersRepository) Insert(_ context.Context, matcher *dao.Matcher) (*dao.Matcher, error) {
	if r.insertErr != nil {
		return nil, r.insertErr
	}
	stored := *matcher
	if stored.ID == uuid.Nil {
		stored.ID = uuid.New()
	}
	r.inserted = append(r.inserted, stored)
	return &stored, nil
}

func (r *fakeMatchersRepository) DeleteByOwnerId(_ context.Context, id uuid.UUID) error {
	r.deletedByOwner = append(r.deletedByOwner, id)
	return nil
}

func (r *fakeMatchersRepository) DeleteByOwnerIds(_ context.Context, ids *[]uuid.UUID) error {
	r.deletedByOwners = append(r.deletedByOwners, *ids)
	return nil
}
