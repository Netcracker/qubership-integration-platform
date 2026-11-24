package fixer

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/synctest"
)

type githubTestTransport func(*http.Request) (*http.Response, error)

func (f githubTestTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

func TestGitHubClientRetriesTransientGETOnce(t *testing.T) {
	for _, tc := range []struct {
		name   string
		method string
		body   any
		first  int
		last   int
		calls  int
	}{
		{"internal server error", http.MethodGet, nil, http.StatusInternalServerError, http.StatusOK, 2},
		{"bad gateway", http.MethodGet, nil, http.StatusBadGateway, http.StatusOK, 2},
		{"service unavailable", http.MethodGet, nil, http.StatusServiceUnavailable, http.StatusOK, 2},
		{"gateway timeout", http.MethodGet, nil, http.StatusGatewayTimeout, http.StatusOK, 2},
		{"persistent failure", http.MethodGet, nil, http.StatusServiceUnavailable, http.StatusBadGateway, 2},
		{"forbidden", http.MethodGet, nil, http.StatusForbidden, http.StatusForbidden, 1},
		{"not implemented", http.MethodGet, nil, http.StatusNotImplemented, http.StatusNotImplemented, 1},
		{"GET with a body", http.MethodGet, map[string]string{"body": "fixture"}, http.StatusServiceUnavailable, http.StatusServiceUnavailable, 1},
		{"POST", http.MethodPost, nil, http.StatusServiceUnavailable, http.StatusServiceUnavailable, 1},
		{"PATCH", http.MethodPatch, nil, http.StatusServiceUnavailable, http.StatusServiceUnavailable, 1},
		{"PUT", http.MethodPut, nil, http.StatusServiceUnavailable, http.StatusServiceUnavailable, 1},
		{"DELETE", http.MethodDelete, nil, http.StatusServiceUnavailable, http.StatusServiceUnavailable, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				calls := 0
				transport := githubTestTransport(func(req *http.Request) (*http.Response, error) {
					calls++
					if req.Method != tc.method || req.URL.RequestURI() != "/repos/org/repo/items?limit=1" {
						t.Errorf("unexpected request %s %s", req.Method, req.URL)
					}
					if req.Header.Get("Authorization") != "Bearer fixture" {
						t.Error("missing authorization")
					}
					if calls == 1 {
						return &http.Response{StatusCode: tc.first, Body: http.NoBody}, nil
					}
					return &http.Response{StatusCode: tc.last, Body: io.NopCloser(strings.NewReader(`{"id":7}`))}, nil
				})
				client, err := NewGitHubClient("org/repo", "fixture", &http.Client{Transport: transport}, "")
				if err != nil {
					t.Fatal(err)
				}
				var result struct{ ID int }
				err = client.Request(t.Context(), tc.method, "/items?limit=1", tc.body, &result)
				if calls != tc.calls {
					t.Errorf("sent %d requests, want %d", calls, tc.calls)
				}
				if tc.last == http.StatusOK {
					if err != nil || result.ID != 7 {
						t.Fatalf("request returned %+v, %v", result, err)
					}
				} else {
					var response *HTTPError
					if !errors.As(err, &response) || response.Status != tc.last {
						t.Fatalf("request returned %v, want HTTP status %d", err, tc.last)
					}
				}
			})
		})
	}
}

func TestGitHubClientCancelsGETRetryDuringDelay(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		calls := 0
		transport := githubTestTransport(func(*http.Request) (*http.Response, error) {
			calls++
			return &http.Response{StatusCode: http.StatusServiceUnavailable, Body: http.NoBody}, nil
		})
		client, err := NewGitHubClient("org/repo", "fixture", &http.Client{Transport: transport}, "")
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		result := make(chan error, 1)
		go func() {
			result <- client.Request(ctx, http.MethodGet, "/items", nil, nil)
		}()

		synctest.Wait()
		cancel()
		if err := <-result; !errors.Is(err, context.Canceled) {
			t.Errorf("request returned %v, want context cancellation", err)
		}
		if calls != 1 {
			t.Errorf("sent %d requests, want 1", calls)
		}
	})
}

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
