package experimentenv

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"sort"
	"strings"
	"time"
)

type Config struct{ Kubeconfig, Context, Namespace, BaseURL string }
type Container struct {
	Name      string                       `json:"name"`
	Image     string                       `json:"image,omitempty"`
	ImageID   string                       `json:"imageID,omitempty"`
	Ready     bool                         `json:"ready"`
	Resources map[string]map[string]string `json:"resources,omitempty"`
}
type Workload struct {
	Name, UID, Phase          string
	Desired, Ready, Available int32
	Containers                []Container
}
type Port struct {
	Name     string `json:"name"`
	Port     int    `json:"port"`
	NodePort int    `json:"nodePort,omitempty"`
	Protocol string `json:"protocol"`
}
type Service struct {
	Name, Type, ClusterIP string
	Ports                 []Port
}
type Node struct {
	Name                  string
	Capacity, Allocatable map[string]string
}
type Probe struct {
	URL    string
	Status int
	Error  string `json:",omitempty"`
}
type Inventory struct {
	ObservedAt                       time.Time
	Context, Namespace, NamespaceUID string
	Deployments, Pods                []Workload
	Services                         []Service
	Nodes                            []Node
	Probes                           []Probe
}

type object struct {
	Metadata struct{ Name, UID string }
	Spec     struct {
		Replicas        int32
		Type, ClusterIP string
		Ports           []Port
		Containers      []Container
		Template        struct {
			Spec struct{ Containers []Container }
		}
	}
	Status struct {
		Phase                            string
		ReadyReplicas, AvailableReplicas int32
		ContainerStatuses                []Container
		Capacity, Allocatable            map[string]string
	}
}

func Discover(ctx context.Context, c Config) (Inventory, error) {
	result := Inventory{ObservedAt: time.Now().UTC(), Context: c.Context, Namespace: c.Namespace}
	if c.Context != "docker-desktop" || c.Namespace != "cip-e2e" || c.Kubeconfig == "" {
		return result, fmt.Errorf("explicit kubeconfig, context docker-desktop, and namespace cip-e2e are required")
	}
	binary, e := exec.LookPath("kubectl")
	if e != nil {
		return result, e
	}
	read := func(resource string, namespaced bool) ([]object, error) {
		args := []string{"--kubeconfig", c.Kubeconfig, "--context", c.Context, "--request-timeout=15s", "get", resource, "-o", "json"}
		if namespaced {
			args = append(args, "--namespace", c.Namespace)
		}
		cmd := exec.CommandContext(ctx, binary, args...)
		cmd.Env = []string{"PATH=/usr/local/bin:/usr/bin:/bin:/snap/bin", "HOME=/nonexistent", "LANG=C.UTF-8"}
		data, err := cmd.Output()
		if err != nil {
			return nil, fmt.Errorf("read Kubernetes %s: %w", resource, err)
		}
		if resource == "namespace/"+c.Namespace {
			var row object
			err = json.Unmarshal(data, &row)
			return []object{row}, err
		}
		var list struct{ Items []object }
		err = json.Unmarshal(data, &list)
		return list.Items, err
	}
	ns, e := read("namespace/"+c.Namespace, false)
	if e != nil {
		return result, e
	}
	result.NamespaceUID = ns[0].Metadata.UID
	for _, kind := range []string{"deployments", "pods", "services", "nodes"} {
		rows, e := read(kind, kind != "nodes")
		if e != nil {
			return result, e
		}
		for _, row := range rows {
			switch kind {
			case "deployments":
				result.Deployments = append(result.Deployments, Workload{Name: row.Metadata.Name, UID: row.Metadata.UID, Desired: row.Spec.Replicas, Ready: row.Status.ReadyReplicas, Available: row.Status.AvailableReplicas, Containers: row.Spec.Template.Spec.Containers})
			case "pods":
				w := Workload{Name: row.Metadata.Name, UID: row.Metadata.UID, Phase: row.Status.Phase, Containers: row.Spec.Containers}
				for i := range w.Containers {
					for _, status := range row.Status.ContainerStatuses {
						if status.Name == w.Containers[i].Name {
							w.Containers[i].ImageID = status.ImageID
							w.Containers[i].Ready = status.Ready
						}
					}
				}
				result.Pods = append(result.Pods, w)
			case "services":
				result.Services = append(result.Services, Service{row.Metadata.Name, row.Spec.Type, row.Spec.ClusterIP, row.Spec.Ports})
			case "nodes":
				result.Nodes = append(result.Nodes, Node{row.Metadata.Name, row.Status.Capacity, row.Status.Allocatable})
			}
		}
	}
	if c.BaseURL != "" {
		base, e := url.Parse(c.BaseURL)
		if e != nil || base.Scheme != "http" || base.User != nil || base.RawQuery != "" || base.Fragment != "" || (base.Hostname() != "localhost" && base.Hostname() != "127.0.0.1") {
			return result, fmt.Errorf("probe base URL must be an unauthenticated local HTTP endpoint")
		}
		client := &http.Client{Timeout: 5 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
		for _, path := range []string{"/", "/e2e/svc/cip-runtime-catalog-v1/actuator/health", "/e2e/svc/cip-engine-v1/actuator/health", "/e2e/svc/cip-sessions-management-v1/actuator/health", "/e2e/svc/cip-testing-service-v1/health"} {
			u := strings.TrimRight(c.BaseURL, "/") + path
			p := Probe{URL: u}
			req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
			response, err := client.Do(req)
			if err != nil {
				p.Error = "endpoint unavailable"
			} else {
				p.Status = response.StatusCode
				response.Body.Close()
			}
			result.Probes = append(result.Probes, p)
		}
	}
	return result, nil
}

func VerifyBaseline(expected, actual Inventory) error {
	if expected.Context != actual.Context || expected.Namespace != actual.Namespace || expected.NamespaceUID == "" || expected.NamespaceUID != actual.NamespaceUID {
		return fmt.Errorf("environment identity differs from baseline")
	}
	images := func(inv Inventory) []string {
		var out []string
		for _, d := range inv.Deployments {
			for _, c := range d.Containers {
				out = append(out, d.Name+"/"+c.Name+"="+c.Image)
			}
		}
		for _, p := range inv.Pods {
			for _, c := range p.Containers {
				out = append(out, "pod-image="+c.Image+"="+c.ImageID)
			}
		}
		sort.Strings(out)
		return out
	}
	if strings.Join(images(expected), "\n") != strings.Join(images(actual), "\n") {
		return fmt.Errorf("deployment images or running image IDs differ from baseline")
	}
	return nil
}

func WriteInventory(filename string, inventory Inventory) error {
	data, e := json.MarshalIndent(inventory, "", "  ")
	if e != nil {
		return e
	}
	return os.WriteFile(filename, append(data, '\n'), 0600)
}
