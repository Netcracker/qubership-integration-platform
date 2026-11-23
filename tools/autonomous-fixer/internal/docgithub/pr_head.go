package docgithub

import (
	"context"
	"fmt"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
)

func (c *Client) MergedPRHead(ctx context.Context, pr docpacket.PR) (docpacket.PRHead, error) {
	const query = `query($owner:String!,$repo:String!,$number:Int!){repository(owner:$owner,name:$repo){pullRequest(number:$number){number merged baseRefName mergeCommit{oid} headRefOid commits(first:1){totalCount}}}}`
	var data struct {
		Repository struct {
			PullRequest struct {
				Number      int
				Merged      bool
				BaseRefName string
				MergeCommit struct{ OID string }
				HeadRefOid  string
				Commits     struct{ TotalCount int }
			}
		}
	}
	owner, repo, _ := strings.Cut(c.Repository, "/")
	if err := c.GraphQL(ctx, query, map[string]any{"owner": owner, "repo": repo, "number": pr.Number}, &data); err != nil {
		return docpacket.PRHead{}, err
	}
	live := data.Repository.PullRequest
	if live.Number != pr.Number || !live.Merged || live.BaseRefName != pr.Target || live.MergeCommit.OID != pr.MergeSHA || !validCommitSHA.MatchString(live.HeadRefOid) || live.Commits.TotalCount < 1 {
		return docpacket.PRHead{}, fmt.Errorf("GitHub returned inconsistent merge or source history for PR #%d", pr.Number)
	}
	return docpacket.PRHead{SHA: live.HeadRefOid, CommitCount: live.Commits.TotalCount}, nil
}
