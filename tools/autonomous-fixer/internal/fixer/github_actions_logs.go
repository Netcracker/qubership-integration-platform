package fixer

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
)

const maxActionsStepLog = 2 << 20

var actionsLogStorage = regexp.MustCompile(`^(?:productionresultssa[0-9]*\.blob\.core\.windows\.net|[a-z0-9-]+\.actions\.githubusercontent\.com)$`)

func (c *GitHubClient) ActionsStepLog(ctx context.Context, jobID int64, step int) (string, error) {
	if jobID <= 0 || step <= 0 {
		return "", fmt.Errorf("invalid Actions job or step ID")
	}
	direct := *c.client
	direct.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	api := *c
	api.client = &direct
	response, err := api.request(ctx, http.MethodGet, fmt.Sprintf("/actions/jobs/%d/steps/%d/logs", jobID, step), nil, nil)
	if response.status != http.StatusFound {
		if err != nil {
			return "", err
		}
		return "", fmt.Errorf("GitHub did not return an Actions log link")
	}
	location, err := url.Parse(response.header.Get("Location"))
	if err != nil || location.Scheme != "https" || !actionsLogStorage.MatchString(location.Host) || location.User != nil || location.Fragment != "" {
		return "", fmt.Errorf("GitHub returned an unsupported Actions log location")
	}
	direct.Jar = nil
	log, err := requestActionsLog(ctx, &direct, location, "")
	if err != nil {
		return "", err
	}
	if log.StatusCode != http.StatusOK {
		log.Body.Close()
		return "", fmt.Errorf("Actions log download returned HTTP %d", log.StatusCode)
	}
	tail := log.ContentLength > maxActionsStepLog
	if tail {
		size := log.ContentLength
		log.Body.Close()
		start, end := size-maxActionsStepLog, size-1
		log, err = requestActionsLog(ctx, &direct, location, fmt.Sprintf("bytes=%d-%d", start, end))
		if err != nil {
			return "", err
		}
		if log.StatusCode != http.StatusPartialContent || log.Header.Get("Content-Range") != fmt.Sprintf("bytes %d-%d/%d", start, end, size) {
			log.Body.Close()
			return "", fmt.Errorf("Actions log storage did not return the requested log tail")
		}
	}
	defer log.Body.Close()
	data, err := io.ReadAll(io.LimitReader(log.Body, maxActionsStepLog+1))
	if err != nil {
		return "", fmt.Errorf("Actions log could not be read")
	}
	if len(data) > maxActionsStepLog {
		return "", fmt.Errorf("Actions log exceeds the download limit")
	}
	value := string(data)
	if tail {
		_, value, _ = strings.Cut(value, "\n")
	}
	return c.RedactGitHubToken(value), nil
}

func requestActionsLog(ctx context.Context, client *http.Client, location *url.URL, byteRange string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, location.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("invalid Actions log request")
	}
	req.Header.Set("Accept-Encoding", "identity")
	if byteRange != "" {
		req.Header.Set("Range", byteRange)
	}
	log, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("Actions log download failed")
	}
	return log, nil
}

func (c *GitHubClient) RedactGitHubToken(value string) string {
	if c.token == "" {
		return value
	}
	return strings.ReplaceAll(value, c.token, "[redacted]")
}

func (c *GitHubClient) ActionsJobURL(runID, jobID int64) string {
	return fmt.Sprintf("https://github.com/%s/actions/runs/%d/job/%d", c.repository, runID, jobID)
}

func (c *GitHubClient) RerunFailedActionsJobs(ctx context.Context, runID int64) error {
	if runID <= 0 {
		return fmt.Errorf("invalid Actions run ID")
	}
	response, err := c.request(ctx, http.MethodPost, fmt.Sprintf("/actions/runs/%d/rerun-failed-jobs", runID), nil, nil)
	if response.status == http.StatusCreated {
		return nil
	}
	return err
}
