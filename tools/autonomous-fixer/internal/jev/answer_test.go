package jev

import (
	"encoding/json"
	"testing"
)

func TestNullProbabilityIsNotZero(t *testing.T) {
	var answer Answer
	if json.Unmarshal([]byte(`{"type":"choice","choice":"fix","probabilities":{"fix":1,"skip":null}}`), &answer) == nil {
		t.Fatal("null probability accepted as zero")
	}
	if err := json.Unmarshal([]byte(`{"type":"choice","choice":"fix","probabilities":{"fix":1,"skip":0}}`), &answer); err != nil {
		t.Fatal(err)
	}
}
