package docgithub

import (
	"context"
	"encoding/json"
	"fmt"
	"path"
	"regexp"
	"strings"
)

var validCommitSHA = regexp.MustCompile(`^[a-f0-9]{40}$`)

func verifyDocumentationHead(ctx context.Context, c *Client, pr pullRequest, originalSHA string) error {
	if err := validateDocumentationPRBody(pr.Body); err != nil {
		return err
	}
	if !validCommitSHA.MatchString(originalSHA) || !validCommitSHA.MatchString(pr.Head.SHA) {
		return fmt.Errorf("documentation PR has an invalid commit identity")
	}
	if originalSHA != pr.Head.SHA {
		var comparison struct {
			Status string `json:"status"`
		}
		if err := c.Request(ctx, "GET", "/compare/"+originalSHA+"..."+pr.Head.SHA, nil, &comparison); err != nil {
			return err
		}
		if comparison.Status != "ahead" && comparison.Status != "identical" {
			return fmt.Errorf("documentation PR head no longer contains the published candidate")
		}
	}
	files, err := c.API.List(ctx, fmt.Sprintf("/pulls/%d/files", pr.Number), "")
	if err != nil {
		return err
	}
	if len(files) == 0 || len(files) != pr.ChangedFiles {
		return fmt.Errorf("PR file listing is empty or incomplete")
	}
	for _, raw := range files {
		var file struct {
			Filename         string `json:"filename"`
			PreviousFilename string `json:"previous_filename"`
		}
		if err = json.Unmarshal(raw, &file); err != nil {
			return err
		}
		if file.Filename == "" {
			return fmt.Errorf("PR file has no name")
		}
		for _, name := range []string{file.Filename, file.PreviousFilename} {
			if name == "" {
				continue
			}
			if !strings.HasPrefix(name, "help/docs/") || path.Clean(name) != name || strings.ContainsAny(name, "\\\x00") {
				return fmt.Errorf("documentation PR changes files outside help")
			}
		}
	}
	var observed pullRequest
	if err = c.Request(ctx, "GET", fmt.Sprintf("/pulls/%d", pr.Number), nil, &observed); err != nil {
		return err
	}
	if observed.Number != pr.Number || observed.User != pr.User || observed.Head.SHA != pr.Head.SHA || observed.Head.Ref != pr.Head.Ref || observed.Head.Repo.FullName != pr.Head.Repo.FullName || observed.Base.Ref != pr.Base.Ref || observed.Base.Repo.FullName != pr.Base.Repo.FullName || observed.Body != pr.Body || observed.ChangedFiles != pr.ChangedFiles || observed.State != pr.State || observed.Merged != pr.Merged {
		return fmt.Errorf("documentation PR changed while its commits and files were checked")
	}
	return nil
}
