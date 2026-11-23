package fixer

import (
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
)

type actionsLogTransport func(*http.Request) (*http.Response, error)

func (f actionsLogTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

func TestActionsStepLogDownloadsOnlyFromGitHubStorageWithoutCredentials(t *testing.T) {
	const token = "fixture-secret"
	for _, test := range []struct {
		label    string
		location string
		allowed  bool
	}{
		{"GitHub log storage", "https://productionresultssa7.blob.core.windows.net/logs/job?sig=fixture", true},
		{"GitHub Actions results", "https://results-receiver.actions.githubusercontent.com/logs/job", true},
		{"unrelated Azure storage", "https://attacker.blob.core.windows.net/logs/job", false},
		{"forged suffix", "https://productionresultssa7.blob.core.windows.net.example.com/logs/job", false},
		{"local address", "https://127.0.0.1:443/logs/job", false},
		{"credentials in URL", "https://user:pass@productionresultssa7.blob.core.windows.net/logs/job", false},
		{"plaintext storage", "http://productionresultssa7.blob.core.windows.net/logs/job", false},
	} {
		t.Run(test.label, func(t *testing.T) {
			requests := 0
			transport := actionsLogTransport(func(req *http.Request) (*http.Response, error) {
				requests++
				if requests == 1 {
					if req.URL.String() != "https://api.github.com/repos/org/repo/actions/jobs/39/steps/7/logs" || req.Header.Get("Authorization") != "Bearer "+token {
						t.Errorf("unexpected API request %s", req.URL)
					}
					return &http.Response{StatusCode: http.StatusFound, Header: http.Header{"Location": {test.location}}, Body: http.NoBody}, nil
				}
				if !test.allowed || req.URL.String() != test.location || req.Header.Get("Authorization") != "" || req.Header.Get("Cookie") != "" {
					t.Error("unexpected storage request or credentials")
				}
				return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader("log " + token))}, nil
			})
			client, err := NewGitHubClient("org/repo", token, &http.Client{Transport: transport}, "")
			if err != nil {
				t.Fatal(err)
			}
			log, err := client.ActionsStepLog(t.Context(), 39, 7)
			if test.allowed {
				if requests != 2 || err != nil || len(log) > maxActionsStepLog || strings.Contains(log, token) || !strings.HasPrefix(log, "log [redacted]") {
					t.Fatalf("storage result has %d requests and %d log bytes with error %v", requests, len(log), err)
				}
			} else if requests != 1 || err == nil || log != "" {
				t.Fatalf("unsafe redirect accepted with %d requests and error %v", requests, err)
			}
		})
	}
}

func TestActionsStepLogUsesABoundedTailForLargeLogs(t *testing.T) {
	const size = maxActionsStepLog + 2345
	const tail = "partial first line\nlast test failure\n"
	for _, supported := range []bool{true, false} {
		t.Run(fmt.Sprintf("range-supported-%v", supported), func(t *testing.T) {
			requests := 0
			transport := actionsLogTransport(func(req *http.Request) (*http.Response, error) {
				requests++
				switch requests {
				case 1:
					return &http.Response{StatusCode: http.StatusFound, Header: http.Header{"Location": {"https://productionresultssa7.blob.core.windows.net/logs/job"}}, Body: http.NoBody}, nil
				case 2:
					return &http.Response{StatusCode: http.StatusOK, ContentLength: size, Body: http.NoBody}, nil
				default:
					if req.Header.Get("Range") != fmt.Sprintf("bytes=2345-%d", size-1) {
						t.Errorf("unexpected log range %q", req.Header.Get("Range"))
					}
					if !supported {
						return &http.Response{StatusCode: http.StatusOK, ContentLength: size, Body: http.NoBody}, nil
					}
					return &http.Response{StatusCode: http.StatusPartialContent, Header: http.Header{"Content-Range": {fmt.Sprintf("bytes 2345-%d/%d", size-1, size)}},
						Body: io.NopCloser(strings.NewReader(tail))}, nil
				}
			})
			client, err := NewGitHubClient("org/repo", "token", &http.Client{Transport: transport}, "")
			if err != nil {
				t.Fatal(err)
			}
			log, err := client.ActionsStepLog(t.Context(), 39, 7)
			if requests != 3 || supported && (err != nil || log != "last test failure\n") || !supported && (err == nil || log != "") {
				t.Fatalf("log %q, %d requests, error %v", log, requests, err)
			}
		})
	}
}
