package docgithub

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func TestAuthorizeDocumentationActors(t *testing.T) {
	for _, row := range []struct {
		allowlist string
		actors    []string
		allowed   bool
	}{
		{" Alice, BOB ", []string{"alice", "Bob"}, true},
		{"alice,bob,", []string{"alice"}, false},
		{"alice,wrong--login", []string{"alice"}, false},
		{"alice bob", []string{"alice"}, false},
		{"alice", []string{"alice", "bob"}, false},
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch r.URL.Path {
			case "/repos/org/repo/actions/variables/AGENT_DOCS_ENABLED":
				_ = json.NewEncoder(w).Encode(map[string]string{"value": "true"})
			case "/repos/org/repo/actions/variables/AGENT_ALLOWED_ACTORS":
				_ = json.NewEncoder(w).Encode(map[string]string{"value": row.allowlist})
			default:
				http.NotFound(w, r)
			}
		}))
		t.Cleanup(server.Close)
		api, err := fixer.NewGitHubClient("org/repo", "test", server.Client(), server.URL)
		if err != nil {
			t.Fatal(err)
		}
		if err = AuthorizeActor(context.Background(), &Client{API: api, Repository: "org/repo"}, row.actors...); (err == nil) != row.allowed {
			t.Fatalf("allowlist %q, actors %v, error %v", row.allowlist, row.actors, err)
		}
	}
}
