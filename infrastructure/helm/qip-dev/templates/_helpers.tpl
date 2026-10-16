{{- /*
The Service of a platform role on port 8080, plus a LoadBalancer copy when loadBalancerPort is set.
Named templates are global, so every subchart includes this one. Takes a dict:
  app              the Service name and the pod label it selects, such as qip-engine-v1
  nodePort         makes the Service a NodePort Service on that port
  loadBalancerPort adds the LoadBalancer Service
  debugPort        also lists JDWP port 5005, unless nodePort is set
*/}}
{{- define "qip-dev.roleService" -}}
apiVersion: v1
kind: Service
metadata:
  labels:
    app: {{ .app }}
  name: {{ .app }}
spec:
  {{- if .nodePort }}
  type: NodePort
  {{- end }}
  ports:
    - name: "8080"
      port: 8080
      targetPort: 8080
      {{- with .nodePort }}
      nodePort: {{ . }}
      {{- end }}
    {{- /* A NodePort Service opens every port it lists on each node, and JDWP takes no credentials. */}}
    {{- if and .debugPort (not .nodePort) }}
    - name: "5005"
      port: 5005
      targetPort: 5005
    {{- end }}
  selector:
    app: {{ .app }}
{{- with .loadBalancerPort }}
---
# Port 8080 again, on a LoadBalancer Service: Docker Desktop's kind-based cluster
# publishes a LoadBalancer port on the host and a NodePort not at all.
apiVersion: v1
kind: Service
metadata:
  labels:
    app: {{ $.app }}
  name: {{ $.app }}-lb
spec:
  type: LoadBalancer
  ports:
    - name: "8080"
      port: {{ . }}
      targetPort: 8080
  selector:
    app: {{ $.app }}
{{- end }}
{{- end }}
