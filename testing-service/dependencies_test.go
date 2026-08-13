package testingservice

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const dependencyScriptPath = "../scripts/check-go-dependencies.sh"

// The hosts below are synthetic. A fixture naming a real vendor source would put
// that name in the history the allowlist exists to keep clean.
const unlistedGoMod = `module example.test/app

go 1.22

require git.examplevendor.test/libs/client v1.4.0
`

const unlistedGoSum = `git.examplevendor.test/libs/client v1.4.0 h1:AAAA=
git.examplevendor.test/libs/client v1.4.0/go.mod h1:BBBB=
`

func runDependencyCheck(t *testing.T, args ...string) scanResult {
	t.Helper()

	if _, err := os.Stat(dependencyScriptPath); err != nil {
		t.Skipf("dependency script not present: %v", err)
	}

	cmd := exec.Command("bash", append([]string{dependencyScriptPath}, args...)...)
	var stderr strings.Builder
	cmd.Stderr = &stderr
	err := cmd.Run()

	var exit *exec.ExitError
	if err != nil && !errors.As(err, &exit) {
		require.NoError(t, err)
	}
	code := 0
	if exit != nil {
		code = exit.ExitCode()
	}
	return scanResult{code: code, stderr: stderr.String()}
}

func writeModule(t *testing.T, goMod string, goSum string) string {
	t.Helper()
	dir := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(dir, "go.mod"), []byte(goMod), 0o600))
	require.NoError(t, os.WriteFile(filepath.Join(dir, "go.sum"), []byte(goSum), 0o600))
	return dir
}

// The allowlist has to keep pace with the module graph, so this is the test that
// fails when a new dependency arrives from a source nobody has vetted yet.
func TestAllowlistAcceptsThisModule(t *testing.T) {
	got := runDependencyCheck(t, ".")

	assert.Equal(t, 0, got.code, got.stderr)
}

func TestAllowlistRejectsAnUnlistedSource(t *testing.T) {
	dir := writeModule(t, unlistedGoMod, unlistedGoSum)

	got := runDependencyCheck(t, dir)

	assert.Equal(t, 1, got.code)
	assert.Contains(t, got.stderr, "outside the dependency allowlist")
}

// A module named only by go.sum still has to pass: that is the file that carries
// the whole graph, direct requirements and transitive ones alike.
func TestAllowlistReadsGoSumAsWellAsGoMod(t *testing.T) {
	dir := writeModule(t, "module example.test/app\n\ngo 1.22\n", unlistedGoSum)

	assert.Equal(t, 1, runDependencyCheck(t, dir).code)
}

func TestAllowlistAcceptsAListedSource(t *testing.T) {
	goMod := "module github.com/Netcracker/example\n\ngo 1.22\n\nrequire github.com/stretchr/testify v1.10.0\n"
	goSum := "github.com/stretchr/testify v1.10.0/go.mod h1:CCCC=\n"
	dir := writeModule(t, goMod, goSum)

	got := runDependencyCheck(t, dir)

	assert.Equal(t, 0, got.code, got.stderr)
}

// Versions, directive keywords and a replace target on the local filesystem are
// not module paths, and reading any of them as one would fail every module.
func TestAllowlistIgnoresWhatIsNotAModulePath(t *testing.T) {
	goMod := `module github.com/Netcracker/example

go 1.22

toolchain go1.22.12

require (
	github.com/google/uuid v1.6.0 // indirect
)

replace github.com/google/uuid => ./vendored/uuid
`
	dir := writeModule(t, goMod, "github.com/google/uuid v1.6.0/go.mod h1:DDDD=\n")

	got := runDependencyCheck(t, dir)

	assert.Equal(t, 0, got.code, got.stderr)
}

func TestAllowlistFailsClosedWithoutAGoSum(t *testing.T) {
	dir := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(dir, "go.mod"), []byte(unlistedGoMod), 0o600))

	got := runDependencyCheck(t, dir)

	assert.Equal(t, 2, got.code)
	assert.Contains(t, got.stderr, "go.sum")
}

func TestAllowlistFailsClosedOnAMissingModuleDirectory(t *testing.T) {
	got := runDependencyCheck(t, filepath.Join(t.TempDir(), "absent"))

	assert.Equal(t, 2, got.code)
	assert.Contains(t, got.stderr, "No module directory")
}
