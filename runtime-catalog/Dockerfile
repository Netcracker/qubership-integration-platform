# syntax=docker/dockerfile:1.7

# The Protobuf toolchain for gRPC specification import. Both versions are pinned here rather than
# taken from Alpine, whose protoc tracks the base image release (3.20 ships 24.4, 3.22 ships 29.4,
# and 29.x gencode needs protobuf-java 4.x) and which packages no grpc-java plugin at all.
# Keep PROTOC_VERSION in step with <protobuf-java.version> and GRPC_JAVA_PLUGIN_VERSION equal to
# <grpc.version>, both in runtime-catalog/pom.xml.
ARG PROTOC_VERSION=25.5
ARG GRPC_JAVA_PLUGIN_VERSION=1.54.2

FROM alpine:3.20 AS protobuf-toolchain
ARG PROTOC_VERSION
ARG GRPC_JAVA_PLUGIN_VERSION

ADD --checksum=sha256:e1ed237a17b2e851cf9662cb5ad02b46e70ff8e060e05984725bc4b4228c6b28 \
    "https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip" \
    /tmp/protoc.zip
ADD --checksum=sha256:eef2f9706940b5a2a23c98472514d2c7f7306c38a97a5ace13a375cec8d16462 --chmod=755 \
    "https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/${GRPC_JAVA_PLUGIN_VERSION}/protoc-gen-grpc-java-${GRPC_JAVA_PLUGIN_VERSION}-linux-x86_64.exe" \
    /toolchain/bin/protoc-gen-grpc-java

# protoc keeps the well-known types beside itself, in <exe dir>/../include, and finds them there
# without being told; a .proto importing google/protobuf/*.proto fails to compile without them.
RUN unzip -q /tmp/protoc.zip -d /toolchain && rm /toolchain/readme.txt

FROM alpine/java:21-jdk

USER root
# protoc is statically linked and needs nothing; the grpc-java plugin is published only as a glibc
# binary, and gcompat supplies its interpreter and the libc it names.
RUN chmod -R 755 /opt/java/openjdk \
    && apk add --no-cache curl=8.14.1-r2 gcompat=1.1.0-r4
COPY --from=protobuf-toolchain /toolchain /usr

# Compile a well-known type through the plugin. protoc spawns the plugin for every file, so this
# fails if the plugin cannot be loaded -- the one thing a version check would not catch, and the
# reason the build should break here rather than the first gRPC import in production.
RUN protoc --plugin=/usr/bin/protoc-gen-grpc-java --grpc-java_out=/tmp \
        --proto_path=/usr/include google/protobuf/empty.proto \
    && rm -rf /tmp/google

USER 10001
VOLUME /tmp

EXPOSE 8080
COPY --chown=10001:0 target/qip-runtime-catalog-*-exec.jar /app/qip-runtime-catalog.jar
CMD ["/opt/java/openjdk/bin/java", "-Xmx512m", "-Djava.security.egd=file:/dev/./urandom", "-Dfile.encoding=UTF-8", "-jar", "/app/qip-runtime-catalog.jar"]
