package experimentruntime

import (
	"context"
	"fmt"
	"os/exec"
	"strings"
)

type Capabilities struct {
	Runtime             string
	Version             string
	Help                string
	ExpectedVersion     string
	VersionMatches      bool
	JSONEvents          bool
	ImageAttachmentFlag bool
}

// ProbeCapabilities runs version and help commands only. It does not resolve models or authenticate.
func ProbeCapabilities(ctx context.Context, runtime, program string, env []string) (Capabilities, error) {
	c := Capabilities{Runtime: runtime}
	var helpArgs []string
	var required []string
	switch runtime {
	case "codex":
		c.ExpectedVersion = "codex-cli 0.166.0"
		helpArgs = []string{"exec", "--help"}
		required = []string{"--ignore-user-config", "--ignore-rules", "--json", "--sandbox", "--cd", "--model"}
	case "opencode":
		c.ExpectedVersion = "opencode v2.2.6"
		helpArgs = []string{"run", "--help"}
		required = []string{"--standalone", "--auto", "--format", "--model", "provider/model#variant"}
	default:
		return c, fmt.Errorf("unsupported runtime %q", runtime)
	}
	read := func(args ...string) (string, error) {
		cmd := exec.CommandContext(ctx, program, args...)
		cmd.Env = append([]string{}, env...)
		out, err := cmd.CombinedOutput()
		return string(out), err
	}
	version, err := read("--version")
	if err != nil {
		return c, fmt.Errorf("read runtime version: %w", err)
	}
	c.Version = strings.TrimSpace(version)
	c.VersionMatches = c.Version == c.ExpectedVersion
	c.Help, err = read(helpArgs...)
	if err != nil {
		return c, fmt.Errorf("read runtime help: %w", err)
	}
	for _, flag := range required {
		if !strings.Contains(c.Help, flag) {
			return c, fmt.Errorf("runtime help does not advertise %s", flag)
		}
	}
	c.JSONEvents = true
	c.ImageAttachmentFlag = strings.Contains(c.Help, "--image") || strings.Contains(c.Help, "--file")
	if !c.VersionMatches {
		return c, fmt.Errorf("runtime version %q does not match %q", c.Version, c.ExpectedVersion)
	}
	return c, nil
}
