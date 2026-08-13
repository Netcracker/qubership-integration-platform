package importexport

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// maxEntrySize bounds how much a single archive entry may expand to, so a
// compression bomb cannot exhaust memory. An exported entity is JSON describing
// one test case or endpoint mock and stays far below this.
const maxEntrySize = 32 << 20

// Importer stores one entity read from an archive and reports the outcome.
type Importer func(entity *model.ExportedEntity) model.ImportResult

// ImportEntitiesFromArchive runs importer over every entry of an uploaded zip
// archive and returns one result per entry. A failure to read the archive itself
// comes back as a single error result rather than as an error.
func ImportEntitiesFromArchive(fileHeader *multipart.FileHeader, importer Importer) []model.ImportResult {
	f, err := fileHeader.Open()
	if err != nil {
		return []model.ImportResult{{Archive: fileHeader.Filename, Result: model.ImportResultError, Message: err.Error()}}
	}
	defer f.Close()

	return ImportEntitiesFromReader(fileHeader.Filename, f, fileHeader.Size, importer)
}

// ImportEntitiesFromReader is ImportEntitiesFromArchive over an already open zip
// archive of the given size.
func ImportEntitiesFromReader(archive string, reader io.ReaderAt, size int64, importer Importer) []model.ImportResult {
	zipReader, err := zip.NewReader(reader, size)
	if err != nil {
		return []model.ImportResult{{Archive: archive, Result: model.ImportResultError, Message: err.Error()}}
	}

	var results []model.ImportResult
	for _, f := range zipReader.File {
		result := importEntityFromFile(f, importer)
		result.Archive = archive
		results = append(results, result)
	}

	return results
}

func importEntityFromFile(file *zip.File, importer Importer) model.ImportResult {
	reader, err := file.Open()
	if err != nil {
		return model.ImportResult{FileName: file.Name, Result: model.ImportResultError, Message: err.Error()}
	}
	defer reader.Close()

	buf := bytes.NewBuffer(nil)
	if _, err = io.Copy(buf, io.LimitReader(reader, maxEntrySize+1)); err != nil {
		return model.ImportResult{FileName: file.Name, Result: model.ImportResultError, Message: err.Error()}
	}
	if buf.Len() > maxEntrySize {
		return model.ImportResult{
			FileName: file.Name,
			Result:   model.ImportResultError,
			Message:  fmt.Sprintf("entry is larger than %d bytes", maxEntrySize),
		}
	}
	var entity model.ExportedEntity
	if err = json.Unmarshal(buf.Bytes(), &entity); err != nil {
		return model.ImportResult{FileName: file.Name, Result: model.ImportResultError, Message: err.Error()}
	}
	result := importer(&entity)
	result.FileName = file.Name
	return result
}
