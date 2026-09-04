FROM alpine/java:21-jdk

# The Protobuf toolchain for gRPC specification import. Both versions are pinned here rather than
# taken from Alpine, whose protoc tracks the base image release (3.20 ships 24.4, 3.22 ships 29.4,
# and 29.x gencode needs protobuf-java 4.x) and which packages no grpc-java plugin at all.
# Keep PROTOC_VERSION in step with <protobuf-java.version> and GRPC_JAVA_PLUGIN_VERSION equal to
# <grpc.version>, both in runtime-catalog/pom.xml.
ARG PROTOC_VERSION=25.5
ARG PROTOC_SHA256=e1ed237a17b2e851cf9662cb5ad02b46e70ff8e060e05984725bc4b4228c6b28
ARG GRPC_JAVA_PLUGIN_VERSION=1.54.2
ARG GRPC_JAVA_PLUGIN_SHA256=eef2f9706940b5a2a23c98472514d2c7f7306c38a97a5ace13a375cec8d16462

USER root
RUN chmod -R 755 /opt/java/openjdk \
    && apk add --no-cache curl=8.14.1-r2 gcompat=1.1.0-r4 \
    # protoc ships statically linked, so musl needs no shim. Unpacking the whole archive into /usr
    # puts the binary where application.yml expects it and the well-known types in /usr/include,
    # which protoc finds through its <exe dir>/../include default path.
    && curl -fsSL -o /tmp/protoc.zip \
        "https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip" \
    && printf '%s  /tmp/protoc.zip\n' "${PROTOC_SHA256}" > /tmp/protoc.zip.sha256 \
    && sha256sum -c /tmp/protoc.zip.sha256 \
    && unzip -q -o /tmp/protoc.zip -d /usr \
    && rm -f /tmp/protoc.zip /tmp/protoc.zip.sha256 /usr/readme.txt \
    && chmod 0755 /usr/bin/protoc \
    # The grpc-java plugin is published only as a glibc binary; gcompat supplies its interpreter
    # and the libc, libm and libpthread it names.
    && curl -fsSL -o /usr/bin/protoc-gen-grpc-java \
        "https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/${GRPC_JAVA_PLUGIN_VERSION}/protoc-gen-grpc-java-${GRPC_JAVA_PLUGIN_VERSION}-linux-x86_64.exe" \
    && printf '%s  /usr/bin/protoc-gen-grpc-java\n' "${GRPC_JAVA_PLUGIN_SHA256}" > /tmp/plugin.sha256 \
    && sha256sum -c /tmp/plugin.sha256 \
    && rm -f /tmp/plugin.sha256 \
    && chmod 0755 /usr/bin/protoc-gen-grpc-java \
    # Generate from a service definition, so a loader failure breaks the build here rather than the
    # first gRPC import in production.
    && mkdir -p /tmp/protoc-check \
    && printf 'syntax = "proto3";\npackage smoke;\nimport "google/protobuf/empty.proto";\nservice S { rpc M (google.protobuf.Empty) returns (google.protobuf.Empty); }\n' \
        > /tmp/protoc-check/smoke.proto \
    && protoc --plugin=/usr/bin/protoc-gen-grpc-java \
        --grpc-java_out=/tmp/protoc-check --java_out=/tmp/protoc-check \
        --proto_path=/tmp/protoc-check /tmp/protoc-check/smoke.proto \
    && test -f /tmp/protoc-check/smoke/SGrpc.java \
    && rm -rf /tmp/protoc-check

USER 10001
VOLUME /tmp

EXPOSE 8080
COPY --chown=10001:0 target/qip-runtime-catalog-*-exec.jar /app/qip-runtime-catalog.jar
CMD ["/opt/java/openjdk/bin/java", "-Xmx512m", "-Djava.security.egd=file:/dev/./urandom", "-Dfile.encoding=UTF-8", "-jar", "/app/qip-runtime-catalog.jar"]
