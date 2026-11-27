package fixer

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func commentState(t *testing.T) *State {
	t.Helper()
	s, err := NewState(Seed{TaskID: "task", Repository: "org/repo", IssueNumber: 1, BaseSHA: strings.Repeat("a", 40), TrustedSHA: strings.Repeat("b", 40), Requirements: "Fix behavior", ScopePaths: []string{"engine/"}}, "human")
	if err != nil {
		t.Fatal(err)
	}
	return s
}
func TestStateCommentEncoding(t *testing.T) {
	state := commentState(t)
	body, err := EncodeStateComment(state)
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := DecodeStateComment(body)
	if err != nil || decoded.TaskID != state.TaskID {
		t.Fatal(decoded, err)
	}
	if _, err = DecodeStateComment(body + "\n" + body); err == nil {
		t.Fatal("accepted duplicate marker")
	}
	data, _ := json.Marshal(state)
	data = append(data[:len(data)-1], []byte(`,"unexpected":true}`)...)
	if _, err = DecodeStateComment("<!-- qip-agent-state:v1 " + base64.StdEncoding.EncodeToString(data) + " -->"); err == nil {
		t.Fatal("accepted unknown state property")
	}
	state.Receipts = []string{strings.Repeat("x", 65536)}
	if _, err = EncodeStateComment(state); err == nil {
		t.Fatal("truncated large state")
	}
}
func TestCommentStorePreflightAndCanonicalIdentity(t *testing.T) {
	state := commentState(t)
	body, _ := EncodeStateComment(state)
	bot := GitHubUser{ID: 3, Login: ActionsBotLogin, Type: "Bot"}
	comments := []Comment{{ID: 10, Body: body, User: bot}}
	writes := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "GET" {
			json.NewEncoder(w).Encode(comments)
			return
		}
		writes++
		json.NewEncoder(w).Encode(Comment{ID: 10})
	}))
	defer server.Close()
	client, _ := NewGitHubClient("org/repo", "token", server.Client(), server.URL)
	store := NewEngineeringCommentStore(client, "org/repo", 1, EngineeringAuthors{Actions: bot}, "task")
	read, err := store.ReadState(context.Background())
	if err != nil || read.Status != "ok" {
		t.Fatal(read, err)
	}
	next := cloneState(state)
	next.Revision++
	live := cloneState(state)
	live.Contract.RequirementsSHA256 = requirementsSHA256("Changed requirements")
	comments[0].Body, _ = EncodeStateComment(live)
	if err = store.WriteState(context.Background(), next); err == nil {
		t.Fatal("overwrote changed state")
	}
	if writes != 0 {
		t.Fatal("issued write after preflight conflict")
	}
	comments[0].Body = body
	comments[0].ID = 11
	read, _ = store.ReadState(context.Background())
	if read.Status != "unknown" {
		t.Fatal("accepted replacement canonical comment")
	}
	comments[0].ID = 10
	read, _ = store.ReadState(context.Background())
	if read.Status != "unknown" {
		t.Fatal("forgot unknown state")
	}
}
func TestCommentStoreIgnoresSpoofedMarker(t *testing.T) {
	state := commentState(t)
	body, _ := EncodeStateComment(state)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode([]Comment{{ID: 1, Body: body, User: GitHubUser{ID: 3, Type: "User"}}, {ID: 2, Body: body, User: GitHubUser{ID: 4, Type: "Bot"}}})
	}))
	defer server.Close()
	client, _ := NewGitHubClient("org/repo", "token", server.Client(), server.URL)
	store := NewEngineeringCommentStore(client, "org/repo", 1, EngineeringAuthors{Actions: GitHubUser{ID: 3, Login: ActionsBotLogin, Type: "Bot"}}, "task")
	read, _ := store.ReadState(context.Background())
	if read.Status != "ok" || read.State != nil {
		t.Fatalf("accepted spoofed state: %+v", read)
	}
}
