package services

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"mime/multipart"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services/importexport"
)

// exportIndent is what one nesting level of an exported file is offset by. Two
// spaces is what the rest of the platform writes: the catalog serializes with
// Jackson's INDENT_OUTPUT, whose default printer indents by two.
const exportIndent = "  "

// importArchives reads every uploaded archive and reports one result per entity
// file they hold.
func importArchives(fileHeaders []*multipart.FileHeader, importer importexport.Importer) *[]model.ImportResult {
	var results []model.ImportResult
	for _, fileHeader := range fileHeaders {
		results = append(results, importexport.ImportEntitiesFromArchive(fileHeader, importer)...)
	}
	return &results
}

// importEntity checks the envelope of one exported entity, reads the payload out
// of it and hands that to save, which stores it and reports what it did. noun
// names the entity in the log and in the message a failing save answers with.
func importEntity[T any](
	ctx context.Context,
	logger *slog.Logger,
	entity *model.ExportedEntity,
	entityType string,
	noun string,
	save func(ctx context.Context, imported *T) (string, error),
) model.ImportResult {
	result := model.ImportResult{EntityID: &entity.ID, EntityName: &entity.Name}
	if entity.Type != entityType {
		result.Result = model.ImportResultError
		result.Message = fmt.Sprintf("wrong entity type: %v", entity.Type)
		return result
	}
	if err := importexport.CheckDataVersion(entity.Version); err != nil {
		result.Result = model.ImportResultError
		result.Message = fmt.Sprintf("failed to migrate data: %v", err.Error())
		return result
	}
	var imported T
	if err := json.Unmarshal(entity.Data, &imported); err != nil {
		result.Result = model.ImportResultError
		result.Message = err.Error()
		return result
	}
	outcome, err := save(ctx, &imported)
	if err != nil {
		result.Result = model.ImportResultError
		if errors.Is(err, ErrInvalidRequest) {
			// The refusal is about the imported file itself, so the importer needs
			// to read it to know what to fix.
			result.Message = err.Error()
			return result
		}
		// The failure is a bun or PostgreSQL message, which names constraints,
		// tables and columns. It belongs in the log, not in a body the caller reads.
		// entity names the kind, so a query on entityId can tell the two apart.
		logger.ErrorContext(ctx, "Cannot import the "+noun, "entity", noun, "entityId", entity.ID, "error", err)
		result.Message = "failed to save the " + noun
		return result
	}
	result.Result = outcome
	return result
}

// writeExportArchive builds the archive an export answers with, asking exported
// for the envelope and the payload of every row. The payload is not always the
// row itself: a test case is read through a view it has to be unwrapped out of.
func writeExportArchive[T any](rows *[]T, exported func(row T) (model.ExportedEntity, any)) (*[]byte, error) {
	var buffer bytes.Buffer
	zipWriter := zip.NewWriter(&buffer)
	for _, row := range *rows {
		entity, payload := exported(row)
		if err := writeExportedEntity(zipWriter, entity, payload); err != nil {
			return nil, err
		}
	}
	if err := zipWriter.Close(); err != nil {
		return nil, err
	}

	data := buffer.Bytes()
	return &data, nil
}

// writeExportedEntity adds one entity file to the archive, carrying payload as
// the entity data.
//
// An exported file is something a person reads, diffs and edits before importing
// it back, so it is indented and ends with a newline. Indenting the entity
// indents the payload with it: Data is a json.RawMessage, which MarshalIndent
// reformats along with everything around it. The importer reads the file with
// Unmarshal, which is indifferent to the whitespace either way.
func writeExportedEntity(zipWriter *zip.Writer, entity model.ExportedEntity, payload any) error {
	payloadData, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	entity.Data = payloadData
	entityData, err := json.MarshalIndent(entity, "", exportIndent)
	if err != nil {
		return err
	}
	f, err := zipWriter.Create(entity.ID.String() + ".json")
	if err != nil {
		return err
	}
	_, err = f.Write(append(entityData, '\n'))
	return err
}
