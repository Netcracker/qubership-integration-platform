package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
)

type datasetPR struct {
	docpacket.PR
	HeadSHA     string `json:"head_sha"`
	CommitCount int    `json:"commit_count"`
}

type dataset struct {
	Repository string `json:"repository"`
	DocsRef    string `json:"docs_ref"`
	Issues     []struct {
		Number                int         `json:"number"`
		Title                 string      `json:"title"`
		Body                  string      `json:"body"`
		URL                   string      `json:"url"`
		PRs                   []datasetPR `json:"prs"`
		Selected              []int       `json:"primary_linked_merged_prs"`
		DocsPaths             []string    `json:"docs_paths"`
		ClassificationSources []string    `json:"classification_sources"`
		TimelineComplete      bool        `json:"timeline_complete"`
	} `json:"issues"`
}

func save(path string, v any) error {
	b, e := json.MarshalIndent(v, "", "  ")
	if e != nil {
		return e
	}
	return os.WriteFile(path, append(b, '\n'), 0600)
}
func run(ctx context.Context, args []string) error {
	f := flag.NewFlagSet("jev-prepare", flag.ContinueOnError)
	input := f.String("dataset", "", "reviewed issue/PR/document selection JSON")
	repo := f.String("repository", "", "local Git repository")
	output := f.String("output", "", "new packet directory")
	autoRetrieve := f.Bool("auto-retrieve", false, "search current documentation and sources in Git")
	maxSource := f.Int("max-source-bytes", 64000, "included current source bytes; omissions mark the packet incomplete")
	maxDiff := f.Int("max-diff-bytes", 160000, "included behavior diff bytes; omissions mark the packet incomplete")
	maxDoc := f.Int("max-doc-bytes", 80000, "included documentation bytes; omissions mark the packet incomplete")
	if e := f.Parse(args); e != nil {
		return e
	}
	if *input == "" || *repo == "" || *output == "" || f.NArg() != 0 {
		return errors.New("dataset, repository, and output are required")
	}
	b, e := os.ReadFile(*input)
	if e != nil {
		return e
	}
	var d dataset
	if e = json.Unmarshal(b, &d); e != nil {
		return e
	}
	if e = os.Mkdir(*output, 0700); e != nil {
		return e
	}
	var failures []error
	for _, item := range d.Issues {
		selected := map[int]bool{}
		for _, n := range item.Selected {
			if selected[n] {
				return fmt.Errorf("duplicate selected PR #%d", n)
			}
			selected[n] = true
		}
		issue := docpacket.Issue{Number: item.Number, Title: item.Title, Body: item.Body, URL: item.URL}
		heads := map[int]docpacket.PRHead{}
		for _, pr := range item.PRs {
			if selected[pr.Number] {
				issue.PRs = append(issue.PRs, pr.PR)
				heads[pr.Number] = docpacket.PRHead{SHA: pr.HeadSHA, CommitCount: pr.CommitCount}
				delete(selected, pr.Number)
			}
		}
		if len(selected) > 0 {
			return fmt.Errorf("issue #%d has selected PRs without source metadata", item.Number)
		}
		p, r, err := docpacket.Build(ctx, docpacket.Config{
			RepositoryPath: *repo, Repository: d.Repository, DocsRef: d.DocsRef, Issue: issue,
			AutoRetrieve: *autoRetrieve, DocsPaths: item.DocsPaths, SourcePaths: item.ClassificationSources,
			MaxDiffBytes: *maxDiff, MaxDocBytes: *maxDoc, MaxSourceBytes: *maxSource,
			ResolvePRHead: func(_ context.Context, pr docpacket.PR) (docpacket.PRHead, error) {
				return heads[pr.Number], nil
			},
		})
		dir := filepath.Join(*output, fmt.Sprintf("issue-%d", item.Number))
		if e = os.Mkdir(dir, 0700); e != nil {
			return e
		}
		if err != nil {
			if e = save(filepath.Join(dir, "error.json"), map[string]any{"error": err.Error()}); e != nil {
				return e
			}
			failures = append(failures, err)
			continue
		}
		if !item.TimelineComplete {
			p.MarkImplementationIncomplete("The issue timeline is incomplete; linked implementing pull requests may be missing.")
		}
		if e = save(filepath.Join(dir, "packet.json"), p); e != nil {
			return e
		}
		if e = save(filepath.Join(dir, "preparation.json"), r); e != nil {
			return e
		}
		if e = json.NewEncoder(os.Stdout).Encode(map[string]any{"issue": item.Number, "complete": p.Complete, "changed_files": len(p.ChangedPaths), "diff_bytes": len(p.Diff), "docs_pages": len(p.Documentation), "limitations": len(p.Limitations)}); e != nil {
			return e
		}
	}
	return errors.Join(failures...)
}
func main() {
	if e := run(context.Background(), os.Args[1:]); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}
