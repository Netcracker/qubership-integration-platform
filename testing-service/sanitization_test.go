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

// The token list the repository really uses is deliberately kept outside the
// repository, so these tests drive the script with a synthetic one. A fixture
// holding a real token would break the very gate it is testing.
const syntheticTokens = `# Synthetic token list. None of these strings mean anything outside this test.
[deny]
git\.examplevendor\.test
bogusproduct

[allow]
allowed-bogusproduct-marker
examplevendor
`

const scriptPath = "../scripts/check-sanitization.sh"

type scanResult struct {
	code   int
	stderr string
}

func runScript(t *testing.T, tokens string, stdin string, args ...string) scanResult {
	t.Helper()

	if _, err := os.Stat(scriptPath); err != nil {
		t.Skipf("sanitization script not present: %v", err)
	}

	cmd := exec.Command("bash", append([]string{scriptPath}, args...)...)
	cmd.Env = append(os.Environ(), "QIP_SANITIZATION_TOKENS="+tokens)
	cmd.Stdin = strings.NewReader(stdin)
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

func writeFile(t *testing.T, name string, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	require.NoError(t, os.WriteFile(path, []byte(content), 0o600))
	return path
}

func tokenList(t *testing.T, content string) string {
	t.Helper()
	return writeFile(t, "tokens.txt", content)
}

func TestScriptAcceptsCleanFiles(t *testing.T) {
	tokens := tokenList(t, syntheticTokens)
	clean := writeFile(t, "clean.txt", "package main\n\nnothing to see here\n")

	got := runScript(t, tokens, "", clean)

	assert.Equal(t, 0, got.code, got.stderr)
}

func TestScriptRejectsAForbiddenToken(t *testing.T) {
	tokens := tokenList(t, syntheticTokens)
	dirty := writeFile(t, "dirty.txt", "first line\nimage: bogusproduct/base:1\nlast line\n")

	got := runScript(t, tokens, "", dirty)

	assert.Equal(t, 1, got.code)
	assert.Contains(t, got.stderr, dirty+":2: forbidden identifier")
}

func TestScriptMatchesCaseInsensitively(t *testing.T) {
	tokens := tokenList(t, syntheticTokens)
	dirty := writeFile(t, "dirty.txt", "title: BogusProduct Testing Service\n")

	assert.Equal(t, 1, runScript(t, tokens, "", dirty).code)
}

func TestScriptDoesNotEchoTheMatch(t *testing.T) {
	tokens := tokenList(t, syntheticTokens)
	dirty := writeFile(t, "dirty.txt", "image: bogusproduct/base:1\n")

	got := runScript(t, tokens, "", dirty)

	assert.NotContains(t, strings.ToLower(got.stderr), "bogusproduct/base")
}

func TestScriptExemptsAnAllowedCarveOut(t *testing.T) {
	tokens := tokenList(t, syntheticTokens)
	clean := writeFile(t, "clean.txt", "header: allowed-bogusproduct-marker\n")

	got := runScript(t, tokens, "", clean)

	assert.Equal(t, 0, got.code, got.stderr)
}

// An allowed string that merely appears inside a longer forbidden one must not
// exempt it: the carve-out for the GitHub organization name would otherwise let
// the vendor's internal host through.
func TestScriptStillRejectsALongerTokenAroundAnAllowedOne(t *testing.T) {
	tokens := tokenList(t, syntheticTokens)
	dirty := writeFile(t, "dirty.txt", "clone from git.examplevendor.test/libs\n")

	assert.Equal(t, 1, runScript(t, tokens, "", dirty).code)
}

func TestScriptScansStdin(t *testing.T) {
	tokens := tokenList(t, syntheticTokens)

	assert.Equal(t, 1, runScript(t, tokens, "+ bogusproduct\n", "--stdin").code)
	assert.Equal(t, 0, runScript(t, tokens, "+ nothing here\n", "--stdin").code)
}

func TestScriptFailsClosedWithoutATokenList(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "absent.txt")
	clean := writeFile(t, "clean.txt", "nothing to see here\n")

	got := runScript(t, missing, "", clean)

	assert.Equal(t, 2, got.code)
	assert.Contains(t, got.stderr, "Cannot read the sanitization token list")
}

func TestScriptFailsClosedOnAnEmptyDenySection(t *testing.T) {
	tokens := tokenList(t, "# nothing forbidden\n[deny]\n[allow]\nsomething\n")
	clean := writeFile(t, "clean.txt", "nothing to see here\n")

	got := runScript(t, tokens, "", clean)

	assert.Equal(t, 2, got.code)
	assert.Contains(t, got.stderr, "no [deny] patterns")
}

func TestScriptFailsClosedOnAPatternOutsideASection(t *testing.T) {
	tokens := tokenList(t, "stray-pattern\n[deny]\nbogusproduct\n")
	clean := writeFile(t, "clean.txt", "nothing to see here\n")

	got := runScript(t, tokens, "", clean)

	assert.Equal(t, 2, got.code)
	assert.Contains(t, got.stderr, "outside a [deny] or [allow] section")
}

func TestScriptReportsWithoutArguments(t *testing.T) {
	tokens := tokenList(t, syntheticTokens)

	got := runScript(t, tokens, "")

	assert.Equal(t, 2, got.code)
	assert.Contains(t, got.stderr, "Usage:")
}
