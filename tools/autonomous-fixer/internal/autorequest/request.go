// Package autorequest identifies a batch of signals for one engineering PR head.
package autorequest

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"regexp"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

const Prefix = "<!-- qip-auto-revision:v1 "

type Request struct {
	RepositoryID int64     `json:"repositoryId"`
	PullID       int64     `json:"pullId"`
	PullNumber   int64     `json:"pullNumber"`
	Head         string    `json:"head"`
	Reviews      []Review  `json:"reviews,omitempty"`
	Failures     []Failure `json:"failures,omitempty"`
}

type Review struct {
	ID    int64            `json:"id"`
	Actor fixer.GitHubUser `json:"actor"`
}

type Failure struct {
	Key     string `json:"key"`
	Name    string `json:"name"`
	URL     string `json:"url"`
	Excerpt string `json:"excerpt"`
}

var commitSHA = regexp.MustCompile(`^[a-f0-9]{40}$`)

func (r Request) valid() bool {
	if r.RepositoryID <= 0 || r.PullID <= 0 || r.PullNumber <= 0 || !commitSHA.MatchString(r.Head) ||
		len(r.Reviews)+len(r.Failures) == 0 {
		return false
	}
	reviews := make(map[int64]bool)
	for _, review := range r.Reviews {
		if review.ID <= 0 || review.Actor.Type != "User" || review.Actor.ID <= 0 || review.Actor.Login == "" || reviews[review.ID] {
			return false
		}
		reviews[review.ID] = true
	}
	failures := make(map[string]bool)
	for _, failure := range r.Failures {
		if failure.Key == "" || failure.Name == "" || failures[failure.Key] {
			return false
		}
		failures[failure.Key] = true
	}
	return true
}

func Encode(r Request) (string, error) {
	if !r.valid() {
		return "", errors.New("invalid automatic revision request")
	}
	raw, err := json.Marshal(r)
	if err != nil {
		return "", err
	}
	line := Prefix + base64.StdEncoding.EncodeToString(raw) + " -->"
	if len(line) > 64000 {
		return "", errors.New("automatic revision request exceeds the pull request comment limit")
	}
	return line, nil
}

func Decode(body string) (Request, error) {
	var r Request
	line, _, _ := strings.Cut(body, "\n")
	value, prefix := strings.CutPrefix(line, Prefix)
	value, suffix := strings.CutSuffix(value, " -->")
	if !prefix || !suffix || len(line) > 64000 {
		return r, errors.New("invalid automatic revision marker")
	}
	raw, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		return r, err
	}
	if err := json.Unmarshal(raw, &r); err != nil {
		return r, err
	}
	if !r.valid() {
		return r, errors.New("invalid automatic revision request")
	}
	return r, nil
}
