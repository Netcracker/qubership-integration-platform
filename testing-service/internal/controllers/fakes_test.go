package controllers

import (
	"context"
	"mime/multipart"

	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services"
)

// The fakes below answer with their zero values unless a test sets the matching
// field. Only the handlers under test touch them, so an unset field that gets
// called means the test exercised a route it did not mean to.

type fakeTestCasesService struct {
	findAll  func(context.Context, *model.SelectionSpecification, model.SortOptions, *model.PaginationOptions, bool) (*[]dao.TestCaseView, error)
	findByID func(context.Context, uuid.UUID) (*dao.TestCaseView, error)
	create   func(context.Context, *dao.TestCase) (*dao.TestCase, error)
	update   func(context.Context, *dao.TestCase) (*dao.TestCase, error)
	delete   func(context.Context, uuid.UUID) error
	export   func(context.Context, *[]uuid.UUID) (*[]byte, error)
}

func (s *fakeTestCasesService) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
	withRelations bool,
) (*[]dao.TestCaseView, error) {
	return s.findAll(ctx, specification, sorting, pagination, withRelations)
}

func (s *fakeTestCasesService) FindById(ctx context.Context, id uuid.UUID) (*dao.TestCaseView, error) {
	return s.findByID(ctx, id)
}

func (s *fakeTestCasesService) Create(ctx context.Context, testCase *dao.TestCase) (*dao.TestCase, error) {
	return s.create(ctx, testCase)
}

func (s *fakeTestCasesService) Update(ctx context.Context, testCase *dao.TestCase) (*dao.TestCase, error) {
	return s.update(ctx, testCase)
}

func (s *fakeTestCasesService) Delete(ctx context.Context, id uuid.UUID) error {
	return s.delete(ctx, id)
}

func (s *fakeTestCasesService) BulkDelete(context.Context, *[]uuid.UUID) error { return nil }

func (s *fakeTestCasesService) Import(
	context.Context,
	[]*multipart.FileHeader,
) (*[]model.ImportResult, error) {
	result := make([]model.ImportResult, 0)
	return &result, nil
}

func (s *fakeTestCasesService) Export(ctx context.Context, ids *[]uuid.UUID) (*[]byte, error) {
	return s.export(ctx, ids)
}

type fakeTestsRunsService struct {
	findAll  func(context.Context, *model.SelectionSpecification, model.SortOptions, *model.PaginationOptions) (*[]dao.TestsRunView, error)
	findByID func(context.Context, uuid.UUID) (*dao.TestsRunView, error)
	startNew func(context.Context, *[]uuid.UUID, string) (*uuid.UUID, error)
	cancel   func(context.Context, uuid.UUID) error
	export   func(context.Context, *[]uuid.UUID) (string, error)
}

func (s *fakeTestsRunsService) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
) (*[]dao.TestsRunView, error) {
	return s.findAll(ctx, specification, sorting, pagination)
}

func (s *fakeTestsRunsService) FindById(ctx context.Context, id uuid.UUID) (*dao.TestsRunView, error) {
	return s.findByID(ctx, id)
}

func (s *fakeTestsRunsService) StartNew(context.Context, *[]uuid.UUID) (*uuid.UUID, error) {
	return nil, nil
}

func (s *fakeTestsRunsService) StartNewFromEntitiesWithType(
	ctx context.Context,
	ids *[]uuid.UUID,
	entityType string,
) (*uuid.UUID, error) {
	return s.startNew(ctx, ids, entityType)
}

func (s *fakeTestsRunsService) Delete(context.Context, uuid.UUID) error        { return nil }
func (s *fakeTestsRunsService) BulkDelete(context.Context, *[]uuid.UUID) error { return nil }

func (s *fakeTestsRunsService) Cancel(ctx context.Context, id uuid.UUID) error {
	return s.cancel(ctx, id)
}

func (s *fakeTestsRunsService) BulkCancel(context.Context, *[]uuid.UUID) error { return nil }

func (s *fakeTestsRunsService) Export(ctx context.Context, ids *[]uuid.UUID) (string, error) {
	return s.export(ctx, ids)
}

func (s *fakeTestsRunsService) RunRetention(context.Context) {}

type fakeTestCaseRunsService struct {
	findAll func(context.Context, *model.SelectionSpecification, model.SortOptions, *model.PaginationOptions) (*[]dao.TestCaseRunView, error)
}

func (s *fakeTestCaseRunsService) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
) (*[]dao.TestCaseRunView, error) {
	return s.findAll(ctx, specification, sorting, pagination)
}

func (s *fakeTestCaseRunsService) FindById(context.Context, uuid.UUID) (*dao.TestCaseRunView, error) {
	return nil, nil
}

func (s *fakeTestCaseRunsService) Cancel(context.Context, uuid.UUID) error        { return nil }
func (s *fakeTestCaseRunsService) BulkCancel(context.Context, *[]uuid.UUID) error { return nil }
func (s *fakeTestCaseRunsService) CancelByTestsRuns(context.Context, *[]uuid.UUID) error {
	return nil
}

func (s *fakeTestCaseRunsService) ClaimNext(context.Context, uuid.UUID, string) (*dao.TestCaseRun, error) {
	return nil, nil
}

func (s *fakeTestCaseRunsService) Finish(context.Context, uuid.UUID, uuid.UUID) error     { return nil }
func (s *fakeTestCaseRunsService) Skip(context.Context, uuid.UUID, uuid.UUID) error       { return nil }
func (s *fakeTestCaseRunsService) RenewLease(context.Context, uuid.UUID, uuid.UUID) error { return nil }

func (s *fakeTestCaseRunsService) ReclaimExpired(context.Context) (int, error) { return 0, nil }

func (s *fakeTestCaseRunsService) Export(context.Context, *[]uuid.UUID) (string, error) {
	return "", nil
}

func (s *fakeTestCaseRunsService) ExportByTestsRunIds(context.Context, *[]uuid.UUID) (string, error) {
	return "", nil
}

func (s *fakeTestCaseRunsService) ExportToCsv(
	context.Context,
	*model.SelectionSpecification,
) (string, error) {
	return "", nil
}

type fakeTestCaseRunErrorsService struct {
	findByTestCaseRunID func(context.Context, uuid.UUID, bool) (*[]dao.ValidationError, error)
}

func (s *fakeTestCaseRunErrorsService) FindByTestCaseRunId(
	ctx context.Context,
	id uuid.UUID,
	withMatchers bool,
) (*[]dao.ValidationError, error) {
	return s.findByTestCaseRunID(ctx, id, withMatchers)
}

func (s *fakeTestCaseRunErrorsService) AddError(
	context.Context,
	uuid.UUID,
	uuid.UUID,
	*dao.Matcher,
	string,
) (*dao.ValidationError, error) {
	return nil, nil
}

func (s *fakeTestCaseRunErrorsService) BulkExport(context.Context, *[]uuid.UUID) (string, error) {
	return "", nil
}

type fakeEndpointMocksService struct {
	findAll func(context.Context, *model.SelectionSpecification, model.SortOptions, *model.PaginationOptions, bool) (*[]dao.EndpointMock, error)
	call    func(context.Context, dao.EndpointReference, model.Exchange) (*model.Exchange, error)
	// create and update stand in only where a test sets them; unset, the mock
	// comes back as it went in.
	create func(context.Context, *dao.EndpointMock) (*dao.EndpointMock, error)
	update func(context.Context, *dao.EndpointMock) (*dao.EndpointMock, error)
}

func (s *fakeEndpointMocksService) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
	withRelations bool,
) (*[]dao.EndpointMock, error) {
	return s.findAll(ctx, specification, sorting, pagination, withRelations)
}

func (s *fakeEndpointMocksService) FindById(context.Context, uuid.UUID) (*dao.EndpointMock, error) {
	return nil, nil
}

func (s *fakeEndpointMocksService) Create(
	ctx context.Context,
	endpointMock *dao.EndpointMock,
) (*dao.EndpointMock, error) {
	if s.create == nil {
		return endpointMock, nil
	}
	return s.create(ctx, endpointMock)
}

func (s *fakeEndpointMocksService) Update(
	ctx context.Context,
	endpointMock *dao.EndpointMock,
) (*dao.EndpointMock, error) {
	if s.update == nil {
		return endpointMock, nil
	}
	return s.update(ctx, endpointMock)
}

func (s *fakeEndpointMocksService) Delete(context.Context, uuid.UUID) error        { return nil }
func (s *fakeEndpointMocksService) BulkDelete(context.Context, *[]uuid.UUID) error { return nil }

func (s *fakeEndpointMocksService) Import(
	context.Context,
	[]*multipart.FileHeader,
) (*[]model.ImportResult, error) {
	result := make([]model.ImportResult, 0)
	return &result, nil
}

func (s *fakeEndpointMocksService) Export(context.Context, *[]uuid.UUID) (*[]byte, error) {
	data := []byte("PK")
	return &data, nil
}

func (s *fakeEndpointMocksService) Call(
	ctx context.Context,
	reference dao.EndpointReference,
	exchange model.Exchange,
) (*model.Exchange, error) {
	return s.call(ctx, reference, exchange)
}

// Compile-time proof that the fakes still match the interfaces they stand in for.
var (
	_ services.TestCasesService         = (*fakeTestCasesService)(nil)
	_ services.TestsRunsService         = (*fakeTestsRunsService)(nil)
	_ services.TestCaseRunsService      = (*fakeTestCaseRunsService)(nil)
	_ services.TestCaseRunErrorsService = (*fakeTestCaseRunErrorsService)(nil)
	_ services.EndpointMocksService     = (*fakeEndpointMocksService)(nil)
)
