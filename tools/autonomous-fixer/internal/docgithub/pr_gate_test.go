package docgithub

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDocumentationGateRequiresPublicationAndHelpOnlyFiles(t *testing.T) {
	for _, damage := range []string{"", "signed_pr", "publishing_before_pr_recorded", "foreign_pr", "foreign_comment", "head", "rename_outside", "incomplete_files", "unmarked", "appended_help", "appended_outside", "rewritten_head", "closing_keyword", "closing_keyword_colon"} {
		t.Run(damage, func(t *testing.T) {
			p := Plan{PacketSHA256: strings.Repeat("c", 64), Version: "docs-plan-v2", IssueState: "closed", Repository: "org/repo", IssueNumber: 1, IssueNodeID: "ISSUE", IssueTitle: "Title", IssueBodyHash: Digest("body"), DefaultBranch: "main", BaseSHA: strings.Repeat("a", 40), Project: Project{ID: "P", ItemID: "I", StatusFieldID: "F", ReviewOptionID: "R", DoneOptionID: "D"}, ImplementingPRs: []docpacket.PR{{Number: 2, MergeSHA: strings.Repeat("a", 40), Target: "main"}}}
			p.ID = Digest(p)
			head := strings.Repeat("b", 40)
			branch := "agent/docs/1/" + p.ID[:12]
			state := Publication{Plan: &p, PlanID: p.ID, PRNumber: 7, Branch: branch, CommitSHA: head, CandidateDigest: "digest", Status: "awaiting-merge"}
			if damage == "publishing_before_pr_recorded" {
				state.PRNumber = 0
				state.Status = "publishing"
			}
			b, _ := json.Marshal(state)
			comment := marker(p) + "\n<!-- qip-docs-state:" + base64.StdEncoding.EncodeToString(b) + " -->"
			author, commentAuthor := "agent-bot", "agent-bot"
			if damage == "foreign_pr" {
				author = "alice"
			}
			if damage == "foreign_comment" {
				commentAuthor = "alice"
			}
			if damage == "head" || damage == "appended_help" || damage == "appended_outside" || damage == "rewritten_head" {
				head = strings.Repeat("c", 40)
			}
			if damage == "unmarked" {
				comment = "ordinary comment"
			}
			count := 1
			if damage == "incomplete_files" {
				count = 2
			}
			body := "Related #1\n" + marker(p)
			switch damage {
			case "signed_pr":
				body += "\n\n<sub>🤖 gpt-6.1-terra high in=300 out=60</sub>"
			case "closing_keyword":
				body += "\nCloses #1"
			case "closing_keyword_colon":
				body += "\nFIXES: another/repo#92"
			}
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				switch {
				case strings.HasSuffix(r.URL.Path, "/pulls/7"):
					json.NewEncoder(w).Encode(map[string]any{"number": 7, "user": map[string]string{"login": author}, "body": body, "changed_files": count, "head": map[string]any{"ref": branch, "sha": head, "repo": map[string]string{"full_name": "org/repo"}}, "base": map[string]any{"ref": "main", "repo": map[string]string{"full_name": "org/repo"}}})
				case strings.HasSuffix(r.URL.Path, "/comments"):
					json.NewEncoder(w).Encode([]any{map[string]any{"body": comment, "user": map[string]string{"login": commentAuthor}}})
				case strings.HasSuffix(r.URL.Path, "/files"):
					file := map[string]string{"filename": "help/docs/readme.md"}
					if damage == "appended_outside" {
						file["filename"] = "engine/pom.xml"
					}
					if damage == "rename_outside" {
						file["previous_filename"] = "engine/pom.xml"
					}
					json.NewEncoder(w).Encode([]any{file})
				case strings.Contains(r.URL.Path, "/compare/"):
					status := "ahead"
					if damage == "rewritten_head" || damage == "head" {
						status = "diverged"
					}
					json.NewEncoder(w).Encode(map[string]string{"status": status})
				default:
					w.WriteHeader(404)
				}
			}))
			defer server.Close()
			api, e := fixer.NewGitHubClient("org/repo", "test", server.Client(), server.URL)
			if e != nil {
				t.Fatal(e)
			}
			e = VerifyDocumentationPR(context.Background(), &Client{API: api, Repository: "org/repo"}, 7, "agent-bot")
			shouldPass := damage == "" || damage == "signed_pr" || damage == "publishing_before_pr_recorded" || damage == "appended_help"
			if (e == nil) != shouldPass {
				t.Fatalf("damage=%s err=%v", damage, e)
			}
		})
	}
}
