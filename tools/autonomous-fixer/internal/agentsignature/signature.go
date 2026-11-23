package agentsignature

import (
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
)

type Signature struct {
	Model  string   `json:"model,omitempty"`
	Effort string   `json:"effort,omitempty"`
	Input  *int64   `json:"input,omitempty"`
	Cached *int64   `json:"cached,omitempty"`
	Output *int64   `json:"output,omitempty"`
	USD    *float64 `json:"usd,omitempty"`
}

var identifier = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_./:-]{0,255}$`)
var effort = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}$`)

func (s Signature) Validate() error {
	if s.Model == "" {
		if s.Effort != "" || s.Input != nil || s.Cached != nil || s.Output != nil || s.USD != nil {
			return fmt.Errorf("agent signature requires an observed model")
		}
		return nil
	}
	if !identifier.MatchString(s.Model) || s.Effort != "" && !effort.MatchString(s.Effort) {
		return fmt.Errorf("invalid model or effort in agent signature")
	}
	for _, v := range []*int64{s.Input, s.Cached, s.Output} {
		if v != nil && *v < 0 {
			return fmt.Errorf("agent signature token counts must be nonnegative")
		}
	}
	if s.USD != nil && (*s.USD < 0 || math.IsNaN(*s.USD) || math.IsInf(*s.USD, 0)) {
		return fmt.Errorf("agent signature cost must be finite and nonnegative")
	}
	return nil
}

func (s Signature) Line() string {
	if s.Model == "" || s.Validate() != nil {
		return ""
	}
	fields := []string{"<sub>🤖", s.Model}
	if s.Effort != "" {
		fields = append(fields, s.Effort)
	}
	for _, f := range []struct {
		key string
		n   *int64
	}{{"in", s.Input}, {"cached", s.Cached}, {"out", s.Output}} {
		if f.n != nil {
			fields = append(fields, f.key+"="+tokens(*f.n))
		}
	}
	if s.USD != nil {
		rounded, _ := strconv.ParseFloat(strconv.FormatFloat(*s.USD, 'g', 2, 64), 64)
		fields = append(fields, "usd="+strconv.FormatFloat(rounded, 'f', -1, 64))
	}
	return strings.Join(fields, " ") + "</sub>"
}

func (s Signature) Append(body string) string {
	body = Strip(body)
	line := s.Line()
	if line == "" {
		return body
	}
	if body == "" {
		return line
	}
	return body + "\n\n" + line
}

func Strip(body string) string {
	for {
		body = strings.TrimRight(body, "\r\n \t")
		i := strings.LastIndexByte(body, '\n') + 1
		text := strings.TrimSpace(body[i:])
		if !strings.HasPrefix(text, "<sub>🤖 ") || !strings.HasSuffix(text, "</sub>") {
			return body
		}
		body = body[:i]
	}
}

func tokens(n int64) string {
	if n < 1000 {
		return strconv.FormatInt(n, 10)
	}
	value := fmt.Sprintf("%d.%03d", n/1000, n%1000)
	return strings.TrimRight(strings.TrimRight(value, "0"), ".") + "k"
}

func add(a, b *int64) *int64 {
	if a == nil || b == nil || *a < 0 || *b < 0 || *a > math.MaxInt64-*b {
		return nil
	}
	value := *a + *b
	return &value
}

func subtract(a, b *int64) *int64 {
	if a == nil || b == nil || *b < 0 || *a < *b {
		return nil
	}
	value := *a - *b
	return &value
}

func addUSD(a, b *float64) *float64 {
	if a == nil || b == nil || *a < 0 || *b < 0 {
		return nil
	}
	value := *a + *b
	if math.IsInf(value, 0) || math.IsNaN(value) {
		return nil
	}
	return &value
}
