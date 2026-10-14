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

// The three dimensions an uploaded archive is bounded on. The per-entry cap
// alone leaves two ways through: many tiny entries, each costing a database
// transaction, and many large ones adding up to far more than any single cap.
//
// The bounds come off what an export weighs. One entry is the JSON of one test
// case or endpoint mock; a deliberately heavy one — an 8 KiB body, twenty
// headers and twenty JSON-schema validation rules — measures about 175 KiB, and
// a typical one is a few KiB. A whole-installation export runs to a few thousand
// entities, so 10,000 entries and 512 MiB across them leave a real export an
// order of magnitude of room while still bounding the work.
//
// The two byte limits are variables so a test can lower them; building half a
// gigabyte of archive to reach the real ones is not worth the seconds it costs.
var (
	maxEntrySize    int64 = 32 << 20
	maxArchiveBytes int64 = 512 << 20
)

const maxArchiveEntries = 10_000

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

	// The entry count is known before anything is read, so an archive over that
	// bound is refused whole rather than half imported.
	if len(zipReader.File) > maxArchiveEntries {
		return []model.ImportResult{{
			Archive: archive,
			Result:  model.ImportResultError,
			Message: fmt.Sprintf("the archive holds %d entries, more than the %d allowed",
				len(zipReader.File), maxArchiveEntries),
		}}
	}

	var results []model.ImportResult
	budget := maxArchiveBytes
	for _, f := range zipReader.File {
		result, read := importEntityFromFile(f, budget, importer)
		result.Archive = archive
		results = append(results, result)
		budget = max(0, budget-read)
	}

	return results
}

// importEntityFromFile reads one entry and reports how much of the archive
// budget it took. An entry is refused when it alone expands past maxEntrySize,
// and when the entries before it have already used the archive budget up. The
// entries the budget covered keep the outcome they got: they are imported.
func importEntityFromFile(file *zip.File, budget int64, importer Importer) (model.ImportResult, int64) {
	reader, err := file.Open()
	if err != nil {
		return model.ImportResult{FileName: file.Name, Result: model.ImportResultError, Message: err.Error()}, 0
	}
	defer reader.Close()

	limit := min(maxEntrySize, budget)
	buf := bytes.NewBuffer(nil)
	read, err := io.Copy(buf, io.LimitReader(reader, limit+1))
	if err != nil {
		return model.ImportResult{FileName: file.Name, Result: model.ImportResultError, Message: err.Error()}, read
	}
	if read > limit {
		message := fmt.Sprintf("entry is larger than %d bytes", maxEntrySize)
		if budget < maxEntrySize {
			message = fmt.Sprintf("the archive expands to more than %d bytes", maxArchiveBytes)
		}
		return model.ImportResult{FileName: file.Name, Result: model.ImportResultError, Message: message}, read
	}
	var entity model.ExportedEntity
	if err = json.Unmarshal(buf.Bytes(), &entity); err != nil {
		return model.ImportResult{FileName: file.Name, Result: model.ImportResultError, Message: err.Error()}, read
	}
	result := importer(&entity)
	result.FileName = file.Name
	return result, read
}
