package importexport

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
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
		Type:    model.ExportedTypeTestCase,
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

// withByteLimits lowers the two byte limits for the length of the test, so a
// case can reach them without half a gigabyte of archive.
func withByteLimits(t *testing.T, entry, archive int64) {
	t.Helper()
	entryWas, archiveWas := maxEntrySize, maxArchiveBytes
	maxEntrySize, maxArchiveBytes = entry, archive
	t.Cleanup(func() { maxEntrySize, maxArchiveBytes = entryWas, archiveWas })
}

// archiveOfEntries builds an archive of count entries, each carrying content.
func archiveOfEntries(t *testing.T, count int, content string) *bytes.Reader {
	t.Helper()
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for i := range count {
		f, err := writer.Create(fmt.Sprintf("entry-%d.json", i))
		require.NoError(t, err)
		_, err = f.Write([]byte(content))
		require.NoError(t, err)
	}
	require.NoError(t, writer.Close())
	return bytes.NewReader(buffer.Bytes())
}

// The per-entry cap leaves an archive of many tiny entries unbounded, and every
// entry costs a database transaction. A few megabytes on the wire used to be
// enough to buy tens of thousands of them.
func TestImportEntitiesFromReaderRefusesAnArchiveOfTooManyEntries(t *testing.T) {
	archive := archiveOfEntries(t, maxArchiveEntries+1, exportedEntityJSON(t, "one of many"))

	results := ImportEntitiesFromReader("bundle.zip", archive, archive.Size(), func(*model.ExportedEntity) model.ImportResult {
		t.Fatal("the importer must not run for an archive over the entry limit")
		return model.ImportResult{}
	})

	require.Len(t, results, 1)
	assert.Equal(t, "bundle.zip", results[0].Archive)
	assert.Equal(t, model.ImportResultError, results[0].Result)
	assert.Contains(t, results[0].Message, strconv.Itoa(maxArchiveEntries))
}

func TestImportEntitiesFromReaderAcceptsAnArchiveAtTheEntryLimit(t *testing.T) {
	archive := archiveOfEntries(t, maxArchiveEntries, exportedEntityJSON(t, "one of many"))

	results := ImportEntitiesFromReader("bundle.zip", archive, archive.Size(), func(*model.ExportedEntity) model.ImportResult {
		return model.ImportResult{Result: model.ImportResultCreated}
	})

	require.Len(t, results, maxArchiveEntries)
	assert.Equal(t, model.ImportResultCreated, results[0].Result)
}

// The entries that fit are imported; the one that runs the budget out and the
// ones after it are refused with the limit named.
func TestImportEntitiesFromReaderStopsWhenTheArchiveExpandsPastTheAggregateLimit(t *testing.T) {
	withByteLimits(t, 32<<20, 4<<10)

	archive := archiveOfEntries(t, 8, exportedEntityJSON(t, strings.Repeat("n", 2<<10)))

	imported := 0
	results := ImportEntitiesFromReader("bundle.zip", archive, archive.Size(), func(*model.ExportedEntity) model.ImportResult {
		imported++
		return model.ImportResult{Result: model.ImportResultCreated}
	})

	require.Len(t, results, 8)
	assert.Less(t, imported, 8, "the budget stops the importer before the last entry")
	assert.Positive(t, imported, "the entries the budget covered are imported")
	last := results[len(results)-1]
	assert.Equal(t, model.ImportResultError, last.Result)
	assert.Contains(t, last.Message, "expands to more than")
}

func TestImportEntitiesFromReaderRefusesAnEntryOverThePerEntryLimit(t *testing.T) {
	withByteLimits(t, 1<<10, 512<<20)

	archive := archiveOf(t, map[string]string{"huge.json": strings.Repeat("x", 4<<10)})

	results := ImportEntitiesFromReader("bundle.zip", archive, archive.Size(), func(*model.ExportedEntity) model.ImportResult {
		t.Fatal("the importer must not run for an entry over the size limit")
		return model.ImportResult{}
	})

	require.Len(t, results, 1)
	assert.Equal(t, model.ImportResultError, results[0].Result)
	assert.Contains(t, results[0].Message, "larger than")
}

func TestImportEntitiesFromReaderReturnsNothingForAnEmptyArchive(t *testing.T) {
	archive := archiveOf(t, nil)

	results := ImportEntitiesFromReader("bundle.zip", archive, archive.Size(), func(*model.ExportedEntity) model.ImportResult {
		t.Fatal("the importer must not run for an empty archive")
		return model.ImportResult{}
	})

	assert.Empty(t, results)
}
