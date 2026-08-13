package dao

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestEffectiveLimitClampsTheRequestedPageSize(t *testing.T) {
	cases := []struct {
		name      string
		requested int
		maxLimit  int
		want      int
	}{
		{"a request within the cap is honored", 5, 20, 5},
		{"a request at the cap is honored", 20, 20, 20},
		{"a request above the cap falls back to it", 500, 20, 20},
		{"an unset limit falls back to the cap", 0, 20, 20},
		{"a negative limit falls back to the cap", -1, 20, 20},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			assert.Equal(t, tc.want, effectiveLimit(tc.requested, tc.maxLimit))
		})
	}
}
