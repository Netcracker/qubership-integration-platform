package autorequest

import (
	"reflect"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func TestBatchRoundTripKeepsReviewAndFailureOnOneHead(t *testing.T) {
	r := Request{
		RepositoryID: 1, PullID: 42, PullNumber: 9, Head: strings.Repeat("a", 40),
		Reviews:  []Review{{ID: 6, Actor: fixer.GitHubUser{ID: 7, Login: "maintainer", Type: "User"}}},
		Failures: []Failure{{Key: "test", Name: "Unit tests", Excerpt: "expected 42, got 7"}},
	}
	marker, err := Encode(r)
	if err != nil {
		t.Fatal(err)
	}
	actual, err := Decode(marker + "\n\nRequest details.")
	if err != nil || !reflect.DeepEqual(r, actual) {
		t.Fatal(actual, err)
	}
	if _, err := Decode("Quoted request:\n" + marker); err == nil {
		t.Fatal("accepted a quoted marker")
	}
	r.Reviews = append(r.Reviews, r.Reviews[0])
	if _, err := Encode(r); err == nil {
		t.Fatal("accepted a duplicate review")
	}
}
