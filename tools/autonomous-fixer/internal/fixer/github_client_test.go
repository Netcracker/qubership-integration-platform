package fixer

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestGitHubClientPaginationAndEmptyResponses(t *testing.T) {
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Header.Get("Authorization") != "Bearer secret" {
			t.Error("missing authorization")
		}
		switch r.URL.Path {
		case "/repos/org/repo/items":
			if r.URL.Query().Get("per_page") != "100" {
				t.Error("missing page size")
			}
			if r.URL.Query().Get("page") == "1" {
				w.Header().Set("Link", `<ignored>; rel="next"`)
				fmt.Fprint(w, `{"items":[{"id":1}]}`)
			} else {
				fmt.Fprint(w, `{"items":[{"id":2}]}`)
			}
		case "/repos/org/repo/empty":
			w.WriteHeader(202)
		case "/repos/org/repo/error":
			w.WriteHeader(403)
			fmt.Fprint(w, "secret response")
		default:
			w.WriteHeader(204)
		}
	}))
	defer server.Close()
	client, err := NewGitHubClient("org/repo", "secret", server.Client(), server.URL)
	if err != nil {
		t.Fatal(err)
	}
	items, err := client.List(context.Background(), "/items?other=x", "items")
	if err != nil || len(items) != 2 {
		t.Fatalf("items %s: %v", items, err)
	}
	if err = client.Request(context.Background(), "POST", "/empty", nil, nil); err != nil {
		t.Fatal(err)
	}
	err = client.Request(context.Background(), "GET", "/error", nil, nil)
	var status *HTTPError
	if !errors.As(err, &status) || status.Status != 403 || strings.Contains(err.Error(), "secret") {
		t.Fatalf("unsafe error: %v", err)
	}
	if calls != 4 {
		t.Fatalf("unexpected retries: %d", calls)
	}
}
func TestGitHubClientRejectsRedirectAndBadCollections(t *testing.T) {
	leaked := false
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { leaked = true }))
	defer target.Close()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "redirect") {
			http.Redirect(w, r, target.URL, 302)
			return
		}
		fmt.Fprint(w, `{"items":null}`)
	}))
	defer server.Close()
	client, _ := NewGitHubClient("org/repo", "secret", server.Client(), server.URL)
	if err := client.Request(context.Background(), "GET", "/redirect", nil, nil); err == nil {
		t.Fatal("accepted redirect")
	}
	if leaked {
		t.Fatal("followed cross-origin redirect")
	}
	if _, err := client.List(context.Background(), "/items", "items"); err == nil {
		t.Fatal("accepted invalid collection")
	}
	for _, path := range []string{"//example.com", "/../../users", "/x#fragment", "/x\\y"} {
		if client.Request(context.Background(), "GET", path, nil, nil) == nil {
			t.Errorf("accepted %q", path)
		}
	}
}
