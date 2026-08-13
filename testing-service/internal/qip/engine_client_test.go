package qip

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestHTTPTriggersURL(t *testing.T) {
	tests := []struct {
		name     string
		address  string
		expected string
	}{
		{name: "plain address", address: "http://qip-engine:8080", expected: "http://qip-engine:8080/routes"},
		{name: "trailing slash", address: "http://qip-engine:8080/", expected: "http://qip-engine:8080/routes"},
		{name: "address with a prefix", address: "http://gateway/engine", expected: "http://gateway/engine/routes"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			address, err := NewEngineClient(test.address).HTTPTriggersURL()
			require.NoError(t, err)
			assert.Equal(t, test.expected, address)
		})
	}
}
