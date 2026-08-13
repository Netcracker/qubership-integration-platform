package importexport

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// archiveOf builds a zip archive whose entries carry the given file contents.
func archiveOf(t *testing.T, files map[string]string) *bytes.Reader {
	t.Helper()
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for name, content := range files {
		f, err := writer.Create(name)
		require.NoError(t, err)
		_, err = f.Write([]byte(content))
		require.NoError(t, err)
	}
	require.NoError(t, writer.Close())
	return bytes.NewReader(buffer.Bytes())
}

func exportedEntityJSON(t *testing.T, name string) string {
	t.Helper()
	entity := model.ExportedEntity{
		Version: 1,
		Type:    model.EntityTypeTestCase,
		ID:      uuid.New(),
		Name:    name,
		Data:    json.RawMessage(`{}`),
	}
	data, err := json.Marshal(entity)
	require.NoError(t, err)
	return string(data)
}

func TestImportEntitiesFromReaderReportsOneResultPerEntry(t *testing.T) {
	archive := archiveOf(t, map[string]string{
		"first.json":  exportedEntityJSON(t, "first"),
		"second.json": exportedEntityJSON(t, "second"),
	})

	var imported []string
	results := ImportEntitiesFromReader("bundle.zip", archive, archive.Size(), func(entity *model.ExportedEntity) model.ImportResult {
		imported = append(imported, entity.Name)
		return model.ImportResult{Result: model.ImportResultCreated}
	})

	require.Len(t, results, 2)
	assert.ElementsMatch(t, []string{"first", "second"}, imported)
	for _, result := range results {
		assert.Equal(t, "bundle.zip", result.Archive)
		assert.Equal(t, model.ImportResultCreated, result.Result)
		assert.Contains(t, []string{"first.json", "second.json"}, result.FileName)
	}
}

func TestImportEntitiesFromReaderReportsMalformedEntries(t *testing.T) {
	archive := archiveOf(t, map[string]string{"broken.json": "not json"})

	results := ImportEntitiesFromReader("bundle.zip", archive, archive.Size(), func(*model.ExportedEntity) model.ImportResult {
		t.Fatal("the importer must not run for a malformed entry")
		return model.ImportResult{}
	})

	require.Len(t, results, 1)
	assert.Equal(t, model.ImportResultError, results[0].Result)
	assert.Equal(t, "broken.json", results[0].FileName)
	assert.NotEmpty(t, results[0].Message)
}

func TestImportEntitiesFromReaderReportsAnArchiveItCannotOpen(t *testing.T) {
	notAnArchive := bytes.NewReader([]byte("plain text"))

	results := ImportEntitiesFromReader("bundle.zip", notAnArchive, notAnArchive.Size(), func(*model.ExportedEntity) model.ImportResult {
		t.Fatal("the importer must not run for an unreadable archive")
		return model.ImportResult{}
	})

	require.Len(t, results, 1)
	assert.Equal(t, "bundle.zip", results[0].Archive)
	assert.Equal(t, model.ImportResultError, results[0].Result)
	assert.NotEmpty(t, results[0].Message)
}

func TestImportEntitiesFromReaderReturnsNothingForAnEmptyArchive(t *testing.T) {
	archive := archiveOf(t, nil)

	results := ImportEntitiesFromReader("bundle.zip", archive, archive.Size(), func(*model.ExportedEntity) model.ImportResult {
		t.Fatal("the importer must not run for an empty archive")
		return model.ImportResult{}
	})

	assert.Empty(t, results)
}
