package services

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"mime/multipart"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services/importexport"
)

// TestCasesService manages test cases: a reference to a chain element, the
// request to send it, and the rules the response is validated against.
type TestCasesService interface {
	FindAll(
		ctx context.Context,
		specification *model.SelectionSpecification,
		sorting model.SortOptions,
		pagination *model.PaginationOptions,
		withRelations bool,
	) (*[]dao.TestCaseView, error)
	FindById(ctx context.Context, id uuid.UUID) (*dao.TestCaseView, error)
	Create(ctx context.Context, testCase *dao.TestCase) (*dao.TestCase, error)
	Update(ctx context.Context, testCase *dao.TestCase) (*dao.TestCase, error)
	Delete(ctx context.Context, id uuid.UUID) error
	BulkDelete(ctx context.Context, ids *[]uuid.UUID) error
	Import(ctx context.Context, fileHeaders []*multipart.FileHeader) (*[]model.ImportResult, error)
	Export(ctx context.Context, ids *[]uuid.UUID) (*[]byte, error)
}

type testCasesService struct {
	runner          dao.Runner
	repositories    Repositories
	matchersService MatchersService
}

// NewTestCasesService returns a TestCasesService over the given database access.
func NewTestCasesService(runner dao.Runner, repositories Repositories, matchersService MatchersService) TestCasesService {
	return &testCasesService{runner: runner, repositories: repositories, matchersService: matchersService}
}

func (s *testCasesService) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
	withRelations bool,
) (*[]dao.TestCaseView, error) {
	return dao.Run(ctx, s.runner, func(ctx context.Context, _ bun.IDB) (*[]dao.TestCaseView, error) {
		return s.repositories.TestCases.FindAll(ctx, specification, sorting, pagination, withRelations)
	})
}

func (s *testCasesService) FindById(ctx context.Context, id uuid.UUID) (*dao.TestCaseView, error) {
	return dao.Run(ctx, s.runner, func(ctx context.Context, _ bun.IDB) (*dao.TestCaseView, error) {
		return s.repositories.TestCases.FindById(ctx, id, true)
	})
}

func (s *testCasesService) Delete(ctx context.Context, id uuid.UUID) error {
	return runInTx(ctx, s.runner, func(ctx context.Context) error {
		if err := s.repositories.TestCases.Delete(ctx, id); err != nil {
			return err
		}
		return s.repositories.Matchers.DeleteByOwnerId(ctx, id)
	})
}

func (s *testCasesService) BulkDelete(ctx context.Context, ids *[]uuid.UUID) error {
	// The matchers go with them: a statement trigger on test_cases removes every
	// matcher owned by a deleted row.
	return runInTx(ctx, s.runner, func(ctx context.Context) error {
		return s.repositories.TestCases.BulkDelete(ctx, ids)
	})
}

func (s *testCasesService) Create(ctx context.Context, testCase *dao.TestCase) (*dao.TestCase, error) {
	return dao.RunInTx(ctx, s.runner, defaultTxOptions(), func(ctx context.Context, _ bun.IDB) (*dao.TestCase, error) {
		return s.doCreate(ctx, testCase)
	})
}

func (s *testCasesService) doCreate(ctx context.Context, testCase *dao.TestCase) (*dao.TestCase, error) {
	createdTestCase, err := s.repositories.TestCases.Insert(ctx, testCase)
	if err != nil {
		return nil, err
	}

	var createdTriggerReference *dao.TriggerReference
	if testCase.TriggerReference != nil {
		testCase.TriggerReference.TestCaseID = createdTestCase.ID
		createdTriggerReference, err = s.repositories.TriggerReferences.Insert(ctx, testCase.TriggerReference)
		if err != nil {
			return nil, err
		}
	}

	var createdRequestSettings *dao.RequestSettings
	if testCase.RequestSettings != nil {
		testCase.RequestSettings.TestCaseID = createdTestCase.ID
		createdRequestSettings, err = s.createRequestSettings(ctx, testCase.RequestSettings)
		if err != nil {
			return nil, err
		}
	}

	createdTestCase.TriggerReference = createdTriggerReference
	createdTestCase.RequestSettings = createdRequestSettings

	rules, err := s.matchersService.Create(ctx, createdTestCase.ID, testCase.ResponseValidationRules)
	if err != nil {
		return nil, err
	}
	createdTestCase.ResponseValidationRules = *rules
	return createdTestCase, nil
}

func (s *testCasesService) Update(ctx context.Context, testCase *dao.TestCase) (*dao.TestCase, error) {
	return dao.RunInTx(ctx, s.runner, defaultTxOptions(), func(ctx context.Context, _ bun.IDB) (*dao.TestCase, error) {
		return s.doUpdate(ctx, testCase)
	})
}

func (s *testCasesService) doUpdate(ctx context.Context, testCase *dao.TestCase) (*dao.TestCase, error) {
	existingTestCase, err := s.repositories.TestCases.FindById(ctx, testCase.ID, true)
	if err != nil {
		return nil, err
	}
	if existingTestCase == nil {
		return nil, fmt.Errorf("test case %v not found", testCase.ID)
	}

	if err = s.repositories.TestCases.Update(ctx, testCase); err != nil {
		return nil, err
	}

	triggerReference, err := s.updateTriggerReference(ctx, &existingTestCase.TestCase, testCase.TriggerReference)
	if err != nil {
		return nil, err
	}
	testCase.TriggerReference = triggerReference

	requestSettings, err := s.updateRequestSettings(ctx, &existingTestCase.TestCase, testCase.RequestSettings)
	if err != nil {
		return nil, err
	}
	testCase.RequestSettings = requestSettings

	if err = s.repositories.Matchers.DeleteByOwnerId(ctx, testCase.ID); err != nil {
		return nil, err
	}

	rules, err := s.matchersService.Create(ctx, testCase.ID, testCase.ResponseValidationRules)
	if err != nil {
		return nil, err
	}
	testCase.ResponseValidationRules = *rules

	return testCase, nil
}

func (s *testCasesService) updateTriggerReference(
	ctx context.Context,
	existingTestCase *dao.TestCase,
	triggerReference *dao.TriggerReference,
) (*dao.TriggerReference, error) {
	if existingTestCase.TriggerReference == nil {
		if triggerReference == nil {
			return nil, nil
		}
		triggerReference.TestCaseID = existingTestCase.ID
		return s.repositories.TriggerReferences.Insert(ctx, triggerReference)
	}
	if triggerReference == nil {
		return nil, s.repositories.TriggerReferences.Delete(ctx, existingTestCase.TriggerReference.ID)
	}
	triggerReference.ID = existingTestCase.TriggerReference.ID
	triggerReference.TestCaseID = existingTestCase.ID
	if err := s.repositories.TriggerReferences.Update(ctx, triggerReference); err != nil {
		return nil, err
	}
	return triggerReference, nil
}

// updateRequestSettings replaces the stored request settings wholesale. Building
// them again is simpler than reconciling the message, headers and parameters
// that hang off them.
func (s *testCasesService) updateRequestSettings(
	ctx context.Context,
	existingTestCase *dao.TestCase,
	requestSettings *dao.RequestSettings,
) (*dao.RequestSettings, error) {
	if existingTestCase.RequestSettings != nil {
		if err := s.repositories.RequestSettings.Delete(ctx, existingTestCase.RequestSettings.ID); err != nil {
			return nil, err
		}
	}
	if requestSettings == nil {
		return nil, nil
	}
	if existingTestCase.RequestSettings != nil {
		requestSettings.ID = existingTestCase.RequestSettings.ID
		requestSettings.TestCaseID = existingTestCase.ID
	}
	return s.createRequestSettings(ctx, requestSettings)
}

func (s *testCasesService) createRequestSettings(
	ctx context.Context,
	requestSettings *dao.RequestSettings,
) (*dao.RequestSettings, error) {
	createdRequestSettings, err := s.repositories.RequestSettings.Insert(ctx, requestSettings)
	if err != nil {
		return nil, err
	}

	createdMessage, err := createMessage(ctx, s.repositories, requestSettings.Message, createdRequestSettings.ID)
	if err != nil {
		return nil, err
	}
	createdRequestSettings.Message = createdMessage

	var pathParameters []dao.PathParameter
	for _, parameter := range requestSettings.PathParameters {
		if parameter == nil {
			continue
		}
		parameter.RequestSettingsID = createdRequestSettings.ID
		pathParameters = append(pathParameters, *parameter)
	}
	if err = s.repositories.PathParameters.BulkInsert(ctx, &pathParameters); err != nil {
		return nil, err
	}
	createdRequestSettings.PathParameters = requestSettings.PathParameters

	var queryParameters []dao.QueryParameter
	for _, parameter := range requestSettings.QueryParameters {
		if parameter == nil {
			continue
		}
		parameter.RequestSettingsID = createdRequestSettings.ID
		queryParameters = append(queryParameters, *parameter)
	}
	if err = s.repositories.QueryParameters.BulkInsert(ctx, &queryParameters); err != nil {
		return nil, err
	}
	createdRequestSettings.QueryParameters = requestSettings.QueryParameters

	return createdRequestSettings, nil
}

func (s *testCasesService) Import(ctx context.Context, fileHeaders []*multipart.FileHeader) (*[]model.ImportResult, error) {
	var importResults []model.ImportResult
	for _, fileHeader := range fileHeaders {
		results := importexport.ImportEntitiesFromArchive(fileHeader, func(entity *model.ExportedEntity) model.ImportResult {
			return s.importTestCase(ctx, entity)
		})
		importResults = append(importResults, results...)
	}
	return &importResults, nil
}

func (s *testCasesService) importTestCase(ctx context.Context, entity *model.ExportedEntity) model.ImportResult {
	result := model.ImportResult{EntityID: &entity.ID, EntityName: &entity.Name}
	if entity.Type != model.EntityTypeTestCase {
		result.Result = model.ImportResultError
		result.Message = fmt.Sprintf("wrong entity type: %v", entity.Type)
		return result
	}
	data, err := importexport.MigrateEntityData(&entity.Data, entity.Version, importexport.GetTestCasesDataMigrations())
	if err != nil {
		result.Result = model.ImportResultError
		result.Message = fmt.Sprintf("failed to migrate data: %v", err.Error())
		return result
	}
	var testCase dao.TestCase
	if err = json.Unmarshal(*data, &testCase); err != nil {
		result.Result = model.ImportResultError
		result.Message = err.Error()
		return result
	}
	outcome, err := dao.RunInTx(ctx, s.runner, defaultTxOptions(), func(ctx context.Context, _ bun.IDB) (string, error) {
		exists, err := s.repositories.TestCases.Exists(ctx, testCase.ID)
		if err != nil {
			return "", err
		}
		if exists {
			_, err = s.doUpdate(ctx, &testCase)
			return model.ImportResultUpdated, err
		}
		_, err = s.doCreate(ctx, &testCase)
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

func (s *testCasesService) Export(ctx context.Context, ids *[]uuid.UUID) (*[]byte, error) {
	specification := model.SelectionSpecification{Ids: ids}
	testCases, err := s.FindAll(ctx, &specification, model.SortOptions{Order: model.OrderAscending}, nil, true)
	if err != nil {
		return nil, err
	}

	dataVersion := importexport.GetTestCasesActualDataVersion()
	var buffer bytes.Buffer
	zipWriter := zip.NewWriter(&buffer)
	for _, testCase := range *testCases {
		entity := model.ExportedEntity{
			Version: dataVersion,
			Type:    model.EntityTypeTestCase,
			ID:      testCase.ID,
			Name:    testCase.Name,
		}
		if err = writeExportedEntity(zipWriter, entity, testCase.TestCase); err != nil {
			return nil, err
		}
	}
	if err = zipWriter.Close(); err != nil {
		return nil, err
	}

	data := buffer.Bytes()
	return &data, nil
}

// writeExportedEntity adds one entity file to the archive, carrying payload as
// the entity data.
func writeExportedEntity(zipWriter *zip.Writer, entity model.ExportedEntity, payload any) error {
	payloadData, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	entity.Data = payloadData
	entityData, err := json.Marshal(entity)
	if err != nil {
		return err
	}
	f, err := zipWriter.Create(entity.ID.String() + ".json")
	if err != nil {
		return err
	}
	_, err = f.Write(entityData)
	return err
}

// createMessage stores a message and its headers under the given owner. Both
// request and response settings own one.
func createMessage(
	ctx context.Context,
	repositories Repositories,
	message *dao.Message,
	ownerID uuid.UUID,
) (*dao.Message, error) {
	if message == nil {
		return nil, nil
	}
	message.OwnerID = ownerID
	createdMessage, err := repositories.Messages.Insert(ctx, message)
	if err != nil {
		return nil, err
	}
	var headers []dao.Header
	for _, header := range message.Headers {
		if header == nil {
			continue
		}
		header.MessageID = createdMessage.ID
		headers = append(headers, *header)
	}
	if err = repositories.Headers.BulkInsert(ctx, &headers); err != nil {
		return nil, err
	}
	createdMessage.Headers = message.Headers
	return createdMessage, nil
}
