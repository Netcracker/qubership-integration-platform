package docgithub

import (
	"context"
	"encoding/json"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestIntakeAcceptsClosedIssueAndRequiresReviewStatus(t *testing.T) {
	for _, status := range []string{"REVIEW", "DONE"} {
		t.Run(status, func(t *testing.T) {
			p := Plan{Repository: "org/repo", IssueTitle: "Title", BaseSHA: strings.Repeat("a", 40), IssueBodyHash: Digest("Issue body"), ImplementingPRs: []docpacket.PR{{Number: 2, MergeSHA: strings.Repeat("a", 40), Target: "main"}}}
			a := &publicationAPI{plan: p, state: "closed", project: status, head: p.BaseSHA}
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/repos/org/repo" {
					json.NewEncoder(w).Encode(map[string]string{"default_branch": "main"})
					return
				}
				a.serve(w, r)
			}))
			defer server.Close()
			api, _ := fixer.NewGitHubClient("org/repo", "test", server.Client(), server.URL)
			v, e := Prepare(context.Background(), &Client{API: api, Repository: "org/repo"}, 1, 12)
			if status == "REVIEW" {
				if e != nil || !v.Complete || v.Plan.Validate() != nil || v.Plan.Project.Number != 12 {
					t.Fatal(v, e)
				}
			} else if e == nil {
				t.Fatal("admitted a Done task")
			}
		})
	}
}
