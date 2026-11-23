package experimentenv

import (
	"fmt"
	"regexp"
	"sort"
)

type RestoreCommand struct {
	Args           []string
	ExpectedOutput string `json:",omitempty"`
}

// BuildRestoreCommands returns argv only. The controller must supply an explicit kubeconfig and stop if the UID guard differs.
func BuildRestoreCommands(expected Inventory) ([]RestoreCommand, error) {
	if expected.Context != "docker-desktop" || expected.Namespace != "cip-e2e" || expected.NamespaceUID == "" {
		return nil, fmt.Errorf("restore requires the allocated namespace identity")
	}
	namePattern := regexp.MustCompile(`^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$`)
	imagePattern := regexp.MustCompile(`^[a-z0-9][a-z0-9./:_-]*(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}|@sha256:[a-f0-9]{64})$`)
	commands := []RestoreCommand{{Args: []string{"--context", expected.Context, "get", "namespace", expected.Namespace, "-o", "jsonpath={.metadata.uid}"}, ExpectedOutput: expected.NamespaceUID}}
	deployments := append([]Workload(nil), expected.Deployments...)
	sort.Slice(deployments, func(i, j int) bool { return deployments[i].Name < deployments[j].Name })
	seen := map[string]bool{}
	for _, d := range deployments {
		if len(d.Name) > 253 || !namePattern.MatchString(d.Name) || seen[d.Name] || len(d.Containers) == 0 {
			return nil, fmt.Errorf("invalid or duplicate restore deployment: %s", d.Name)
		}
		seen[d.Name] = true
		args := []string{"--context", expected.Context, "--namespace", expected.Namespace, "set", "image", "deployment/" + d.Name}
		containers := map[string]bool{}
		for _, c := range d.Containers {
			if len(c.Name) > 63 || !namePattern.MatchString(c.Name) || containers[c.Name] || !imagePattern.MatchString(c.Image) {
				return nil, fmt.Errorf("invalid restore container or image in deployment %s", d.Name)
			}
			containers[c.Name] = true
			args = append(args, c.Name+"="+c.Image)
		}
		commands = append(commands, RestoreCommand{Args: args}, RestoreCommand{Args: []string{"--context", expected.Context, "--namespace", expected.Namespace, "rollout", "status", "deployment/" + d.Name, "--timeout=180s"}})
	}
	return commands, nil
}
