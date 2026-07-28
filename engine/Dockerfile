FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.3.7

USER root
RUN apk add --no-cache curl=8.21.0-r0

USER 10001
VOLUME /tmp

EXPOSE 8080
COPY --chown=10001:0 target/qip-engine-*-exec.jar /app/qip-engine.jar
CMD ["/opt/java/openjdk/bin/java", "-Xmx832m", "-Djava.security.egd=file:/dev/./urandom", "-Dfile.encoding=UTF-8", "-jar", "/app/qip-engine.jar"]
