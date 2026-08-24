package httpfield

import (
	"strconv"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestIsNameAcceptsATokenAndRejectsAnythingElse(t *testing.T) {
	valid := []string{"Accept", "content-type", "X-Mocked", "x_mocked!", "X-Trace.1", "a`b|c~d", "0"}
	invalid := []string{"", " ", "X Mocked", "X\tMocked", "X:Mocked", "Accept(json)", "X-Mocked\n", "заголовок"}
	for _, name := range valid {
		t.Run("valid/"+strconv.Quote(name), func(t *testing.T) {
			assert.True(t, IsName(name))
		})
	}
	for _, name := range invalid {
		t.Run("invalid/"+strconv.Quote(name), func(t *testing.T) {
			assert.False(t, IsName(name))
		})
	}
}

func TestIsValueRejectsTheControlCharactersThatBreakAHeaderLine(t *testing.T) {
	valid := []string{"", "text/plain", "a b", "a\tb", "ключ"}
	invalid := []string{"a\nb", "a\rb", "a\x00b", "a\x7fb"}
	for _, value := range valid {
		t.Run("valid/"+strconv.Quote(value), func(t *testing.T) {
			assert.True(t, IsValue(value))
		})
	}
	for _, value := range invalid {
		t.Run("invalid/"+strconv.Quote(value), func(t *testing.T) {
			assert.False(t, IsValue(value))
		})
	}
}
