FROM alpine/java:21-jdk

USER root
# The Protobuf toolchain for gRPC specification import comes from target/, where Maven resolves it
# from Central so its version stays tied to the libraries the generated code is compiled and run
# against. protoc is statically linked and needs nothing; the grpc-java plugin is published only as
# a glibc binary, and gcompat supplies its interpreter and the libc it names.
RUN chmod -R 755 /opt/java/openjdk \
    && apk add --no-cache curl=8.14.1-r2 gcompat=1.1.0-r4
COPY --chmod=755 target/protobuf-toolchain/bin/ /usr/bin/
COPY target/protobuf-toolchain/include/ /usr/include/

# Compile a well-known type through the plugin. protoc spawns the plugin for every file it reads,
# so this fails if the plugin cannot be loaded -- the one thing a version check would not catch,
# and the reason the build should break here rather than the first gRPC import in production.
RUN protoc --plugin=/usr/bin/protoc-gen-grpc-java --grpc-java_out=/tmp \
        --proto_path=/usr/include google/protobuf/empty.proto \
    && rm -rf /tmp/google

USER 10001
VOLUME /tmp

EXPOSE 8080
COPY --chown=10001:0 target/qip-runtime-catalog-*-exec.jar /app/qip-runtime-catalog.jar
CMD ["/opt/java/openjdk/bin/java", "-Xmx512m", "-Djava.security.egd=file:/dev/./urandom", "-Dfile.encoding=UTF-8", "-jar", "/app/qip-runtime-catalog.jar"]
