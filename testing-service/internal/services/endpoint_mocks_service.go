package services

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"mime/multipart"
	"net/http"
	"slices"
	"time"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/matching"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services/importexport"
)

// requestStartKey addresses the moment the mocked call reached the service.
type requestStartKey struct{}

// WithRequestStart returns a copy of ctx carrying the moment the mocked call
// arrived. Call answers a mock with a delay measured from that moment, so the
// time already spent on the request counts towards it.
func WithRequestStart(ctx context.Context, start time.Time) context.Context {
	return context.WithValue(ctx, requestStartKey{}, start)
}

func requestStart(ctx context.Context) time.Time {
	start, ok := ctx.Value(requestStartKey{}).(time.Time)
	if !ok {
		return time.Now()
	}
	return start
}

// EndpointMocksService manages endpoint mocks and answers the calls the engine
// intercepts on their behalf.
type EndpointMocksService interface {
	FindAll(
		ctx context.Context,
		specification *model.SelectionSpecification,
		sorting model.SortOptions,
		pagination *model.PaginationOptions,
		withRelations bool,
	) (*[]dao.EndpointMock, error)
	FindById(ctx context.Context, id uuid.UUID) (*dao.EndpointMock, error)
	Create(ctx context.Context, endpointMock *dao.EndpointMock) (*dao.EndpointMock, error)
	Update(ctx context.Context, endpointMock *dao.EndpointMock) (*dao.EndpointMock, error)
	Delete(ctx context.Context, id uuid.UUID) error
	BulkDelete(ctx context.Context, ids *[]uuid.UUID) error
	Import(ctx context.Context, fileHeaders []*multipart.FileHeader) (*[]model.ImportResult, error)
	Export(ctx context.Context, ids *[]uuid.UUID) (*[]byte, error)
	// Call answers exchange with the response of the first enabled mock on the
	// given endpoint whose matchers all pass. When none matches, the answer is
	// 404: the engine never falls back to the real endpoint.
	Call(ctx context.Context, reference dao.EndpointReference, exchange model.Exchange) (*model.Exchange, error)
}

type endpointMocksService struct {
	runner          dao.Runner
	repositories    Repositories
	matchersService MatchersService
}

// NewEndpointMocksService returns an EndpointMocksService over the given database
// access.
func NewEndpointMocksService(runner dao.Runner, repositories Repositories, matchersService MatchersService) EndpointMocksService {
	return &endpointMocksService{runner: runner, repositories: repositories, matchersService: matchersService}
}

func (s *endpointMocksService) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
	withRelations bool,
) (*[]dao.EndpointMock, error) {
	return dao.Run(ctx, s.runner, func(ctx context.Context, _ bun.IDB) (*[]dao.EndpointMock, error) {
		return s.repositories.EndpointMocks.FindAll(ctx, specification, sorting, pagination, withRelations)
	})
}

func (s *endpointMocksService) FindById(ctx context.Context, id uuid.UUID) (*dao.EndpointMock, error) {
	return dao.Run(ctx, s.runner, func(ctx context.Context, _ bun.IDB) (*dao.EndpointMock, error) {
		return s.repositories.EndpointMocks.FindById(ctx, id, true)
	})
}

func (s *endpointMocksService) Delete(ctx context.Context, id uuid.UUID) error {
	return runInTx(ctx, s.runner, func(ctx context.Context) error {
		if err := s.repositories.EndpointMocks.Delete(ctx, id); err != nil {
			return err
		}
		return s.repositories.Matchers.DeleteByOwnerId(ctx, id)
	})
}

func (s *endpointMocksService) BulkDelete(ctx context.Context, ids *[]uuid.UUID) error {
	return runInTx(ctx, s.runner, func(ctx context.Context) error {
		if err := s.repositories.EndpointMocks.BulkDelete(ctx, ids); err != nil {
			return err
		}
		return s.repositories.Matchers.DeleteByOwnerIds(ctx, ids)
	})
}

func (s *endpointMocksService) Create(ctx context.Context, endpointMock *dao.EndpointMock) (*dao.EndpointMock, error) {
	return dao.RunInTx(ctx, s.runner, defaultTxOptions(), func(ctx context.Context, _ bun.IDB) (*dao.EndpointMock, error) {
		return s.doCreate(ctx, endpointMock)
	})
}

func (s *endpointMocksService) doCreate(ctx context.Context, endpointMock *dao.EndpointMock) (*dao.EndpointMock, error) {
	createdEndpointMock, err := s.repositories.EndpointMocks.Insert(ctx, endpointMock)
	if err != nil {
		return nil, err
	}

	var createdEndpointReference *dao.EndpointReference
	if endpointMock.EndpointReference != nil {
		endpointMock.EndpointReference.EndpointMockID = createdEndpointMock.ID
		createdEndpointReference, err = s.repositories.EndpointReferences.Insert(ctx, endpointMock.EndpointReference)
		if err != nil {
			return nil, err
		}
	}

	var createdResponseSettings *dao.ResponseSettings
	if endpointMock.ResponseSettings != nil {
		endpointMock.ResponseSettings.EndpointMockID = createdEndpointMock.ID
		createdResponseSettings, err = s.createResponseSettings(ctx, endpointMock.ResponseSettings)
		if err != nil {
			return nil, err
		}
	}

	createdEndpointMock.EndpointReference = createdEndpointReference
	createdEndpointMock.ResponseSettings = createdResponseSettings

	requestMatchers, err := s.matchersService.Create(ctx, createdEndpointMock.ID, endpointMock.RequestMatchers)
	if err != nil {
		return nil, err
	}
	createdEndpointMock.RequestMatchers = *requestMatchers
	return createdEndpointMock, nil
}

func (s *endpointMocksService) Update(ctx context.Context, endpointMock *dao.EndpointMock) (*dao.EndpointMock, error) {
	return dao.RunInTx(ctx, s.runner, defaultTxOptions(), func(ctx context.Context, _ bun.IDB) (*dao.EndpointMock, error) {
		return s.doUpdate(ctx, endpointMock)
	})
}

func (s *endpointMocksService) doUpdate(ctx context.Context, endpointMock *dao.EndpointMock) (*dao.EndpointMock, error) {
	existingEndpointMock, err := s.repositories.EndpointMocks.FindById(ctx, endpointMock.ID, true)
	if err != nil {
		return nil, err
	}
	if existingEndpointMock == nil {
		return nil, fmt.Errorf("endpoint mock %v not found", endpointMock.ID)
	}

	if err = s.repositories.EndpointMocks.Update(ctx, endpointMock); err != nil {
		return nil, err
	}

	endpointReference, err := s.updateEndpointReference(ctx, existingEndpointMock, endpointMock.EndpointReference)
	if err != nil {
		return nil, err
	}
	endpointMock.EndpointReference = endpointReference

	responseSettings, err := s.updateResponseSettings(ctx, existingEndpointMock, endpointMock.ResponseSettings)
	if err != nil {
		return nil, err
	}
	endpointMock.ResponseSettings = responseSettings

	if err = s.repositories.Matchers.DeleteByOwnerId(ctx, endpointMock.ID); err != nil {
		return nil, err
	}

	matchers, err := s.matchersService.Create(ctx, endpointMock.ID, endpointMock.RequestMatchers)
	if err != nil {
		return nil, err
	}
	endpointMock.RequestMatchers = *matchers

	return endpointMock, nil
}

func (s *endpointMocksService) updateEndpointReference(
	ctx context.Context,
	existingEndpointMock *dao.EndpointMock,
	endpointReference *dao.EndpointReference,
) (*dao.EndpointReference, error) {
	if existingEndpointMock.EndpointReference == nil {
		if endpointReference == nil {
			return nil, nil
		}
		endpointReference.EndpointMockID = existingEndpointMock.ID
		return s.repositories.EndpointReferences.Insert(ctx, endpointReference)
	}
	if endpointReference == nil {
		return nil, s.repositories.EndpointReferences.Delete(ctx, existingEndpointMock.EndpointReference.ID)
	}
	endpointReference.ID = existingEndpointMock.EndpointReference.ID
	endpointReference.EndpointMockID = existingEndpointMock.ID
	if err := s.repositories.EndpointReferences.Update(ctx, endpointReference); err != nil {
		return nil, err
	}
	return endpointReference, nil
}

// updateResponseSettings replaces the stored response settings wholesale, for the
// same reason updateRequestSettings does.
func (s *endpointMocksService) updateResponseSettings(
	ctx context.Context,
	existingEndpointMock *dao.EndpointMock,
	responseSettings *dao.ResponseSettings,
) (*dao.ResponseSettings, error) {
	if existingEndpointMock.ResponseSettings != nil {
		if err := s.repositories.ResponseSettings.Delete(ctx, existingEndpointMock.ResponseSettings.ID); err != nil {
			return nil, err
		}
	}
	if responseSettings == nil {
		return nil, nil
	}
	if existingEndpointMock.ResponseSettings != nil {
		responseSettings.ID = existingEndpointMock.ResponseSettings.ID
		responseSettings.EndpointMockID = existingEndpointMock.ID
	}
	return s.createResponseSettings(ctx, responseSettings)
}

func (s *endpointMocksService) createResponseSettings(
	ctx context.Context,
	responseSettings *dao.ResponseSettings,
) (*dao.ResponseSettings, error) {
	createdResponseSettings, err := s.repositories.ResponseSettings.Insert(ctx, responseSettings)
	if err != nil {
		return nil, err
	}
	createdMessage, err := createMessage(ctx, s.repositories, responseSettings.Message, createdResponseSettings.ID)
	if err != nil {
		return nil, err
	}
	createdResponseSettings.Message = createdMessage
	return createdResponseSettings, nil
}

func (s *endpointMocksService) Import(ctx context.Context, fileHeaders []*multipart.FileHeader) (*[]model.ImportResult, error) {
	var importResults []model.ImportResult
	for _, fileHeader := range fileHeaders {
		results := importexport.ImportEntitiesFromArchive(fileHeader, func(entity *model.ExportedEntity) model.ImportResult {
			return s.importEndpointMock(ctx, entity)
		})
		importResults = append(importResults, results...)
	}
	return &importResults, nil
}

func (s *endpointMocksService) importEndpointMock(ctx context.Context, entity *model.ExportedEntity) model.ImportResult {
	result := model.ImportResult{EntityID: &entity.ID, EntityName: &entity.Name}
	if entity.Type != model.EntityTypeEndpointMock {
		result.Result = model.ImportResultError
		result.Message = fmt.Sprintf("wrong entity type: %v", entity.Type)
		return result
	}
	data, err := importexport.MigrateEntityData(&entity.Data, entity.Version, importexport.GetEndpointMocksDataMigrations())
	if err != nil {
		result.Result = model.ImportResultError
		result.Message = fmt.Sprintf("failed to migrate data: %v", err.Error())
		return result
	}
	var endpointMock dao.EndpointMock
	if err = json.Unmarshal(*data, &endpointMock); err != nil {
		result.Result = model.ImportResultError
		result.Message = err.Error()
		return result
	}
	outcome, err := dao.RunInTx(ctx, s.runner, defaultTxOptions(), func(ctx context.Context, _ bun.IDB) (string, error) {
		exists, err := s.repositories.EndpointMocks.Exists(ctx, endpointMock.ID)
		if err != nil {
			return "", err
		}
		if exists {
			_, err = s.doUpdate(ctx, &endpointMock)
			return model.ImportResultUpdated, err
		}
		_, err = s.doCreate(ctx, &endpointMock)
		return model.ImportResultCreated, err
	})
	if err != nil {
		result.Result = model.ImportResultError
		result.Message = err.Error()
		return result
	}
	result.Result = outcome
	return result
}

func (s *endpointMocksService) Export(ctx context.Context, ids *[]uuid.UUID) (*[]byte, error) {
	specification := model.SelectionSpecification{Ids: ids}
	endpointMocks, err := s.FindAll(ctx, &specification, model.SortOptions{Order: model.OrderAscending}, nil, true)
	if err != nil {
		return nil, err
	}

	dataVersion := importexport.GetEndpointMocksActualDataVersion()
	var buffer bytes.Buffer
	zipWriter := zip.NewWriter(&buffer)
	for _, endpointMock := range *endpointMocks {
		entity := model.ExportedEntity{
			Version: dataVersion,
			Type:    model.EntityTypeEndpointMock,
			ID:      endpointMock.ID,
			Name:    endpointMock.Name,
		}
		if err = writeExportedEntity(zipWriter, entity, endpointMock); err != nil {
			return nil, err
		}
	}
	if err = zipWriter.Close(); err != nil {
		return nil, err
	}

	data := buffer.Bytes()
	return &data, nil
}

func (s *endpointMocksService) Call(
	ctx context.Context,
	reference dao.EndpointReference,
	exchange model.Exchange,
) (*model.Exchange, error) {
	filters := []model.Filter{
		{Feature: "chain_id", Condition: model.ConditionIs, Values: []string{reference.ChainID}},
		{Feature: "element_id", Condition: model.ConditionIs, Values: []string{reference.ElementID}},
		{Feature: "enabled", Condition: model.ConditionIs, Values: []string{"true"}},
	}
	specification := model.SelectionSpecification{Filters: &filters}
	sorting := model.SortOptions{Order: model.OrderAscending}
	endpointMocks, err := s.FindAll(ctx, &specification, sorting, nil, true)
	if err != nil {
		return nil, err
	}

	slices.SortFunc(*endpointMocks, compareEndpointMocksByMatcherCountAndThenByCreationTime)

	for _, endpointMock := range *endpointMocks {
		matches, err := exchangeMatchesAll(exchange, endpointMock.RequestMatchers)
		if err != nil {
			return nil, err
		}
		if !matches {
			continue
		}
		if err = awaitResponseDelay(ctx, endpointMock.ResponseSettings); err != nil {
			return nil, err
		}
		return buildResponseExchange(endpointMock.ResponseSettings), nil
	}

	return &model.Exchange{Status: http.StatusNotFound}, nil
}

// awaitResponseDelay holds the answer back until the configured delay has passed
// since the call arrived. It gives up when the caller does.
func awaitResponseDelay(ctx context.Context, responseSettings *dao.ResponseSettings) error {
	if responseSettings == nil || responseSettings.Delay <= 0 {
		return nil
	}
	remaining := time.Duration(responseSettings.Delay)*time.Millisecond - time.Since(requestStart(ctx))
	if remaining <= 0 {
		return nil
	}
	timer := time.NewTimer(remaining)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// compareEndpointMocksByMatcherCountAndThenByCreationTime puts the most specific
// mock first: the one with the most enabled matchers, and among equals the oldest.
func compareEndpointMocksByMatcherCountAndThenByCreationTime(m1, m2 dao.EndpointMock) int {
	diff := getEnabledMatchersCount(m2.RequestMatchers) - getEnabledMatchersCount(m1.RequestMatchers)
	if diff != 0 {
		return diff
	}
	if m1.CreatedAt == nil && m2.CreatedAt == nil {
		return 0
	}
	if m1.CreatedAt == nil {
		return -1
	}
	if m2.CreatedAt == nil {
		return 1
	}
	return m1.CreatedAt.Compare(*m2.CreatedAt)
}

func getEnabledMatchersCount(matchers []*dao.Matcher) int {
	count := 0
	for _, matcher := range matchers {
		if matcher != nil && matcher.Enabled {
			count++
		}
	}
	return count
}

func exchangeMatchesAll(exchange model.Exchange, matchers []*dao.Matcher) (bool, error) {
	for _, matcher := range matchers {
		if matcher == nil || !matcher.Enabled {
			continue
		}
		matches, err := exchangeMatches(exchange, matcher)
		if err != nil {
			return false, err
		}
		if !matches {
			return false, nil
		}
	}
	return true, nil
}

func exchangeMatches(exchange model.Exchange, matcher *dao.Matcher) (bool, error) {
	var name string
	if matcher.EntityName != nil {
		name = *matcher.EntityName
	}
	dataGetter, err := matching.GetEntityDataGetter(matcher.EntityType, name)
	if err != nil {
		return false, err
	}
	data, err := dataGetter.GetData(exchange)
	if err != nil {
		return false, err
	}
	predicate, err := matching.GetMatcherPredicate(matcher.Type, buildParametersMap(matcher.Parameters))
	if err != nil {
		return false, err
	}
	return predicate.Test(data) == nil, nil
}

func buildResponseExchange(responseSettings *dao.ResponseSettings) *model.Exchange {
	status := http.StatusOK
	headers := map[string][]string{}
	var body []byte
	if responseSettings != nil {
		status = responseSettings.Status
		if responseSettings.Message != nil {
			if responseSettings.Message.Body != nil {
				body = []byte(*responseSettings.Message.Body)
			}
			for _, header := range responseSettings.Message.Headers {
				if header == nil {
					continue
				}
				name := http.CanonicalHeaderKey(header.Name)
				headers[name] = append(headers[name], header.Value)
			}
		}
	}
	return &model.Exchange{Status: status, Headers: headers, Body: body}
}
