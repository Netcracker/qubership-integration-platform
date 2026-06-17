FROM alpine/java:21-jdk@sha256:366ad7df4fafeca51290ccd7d6f046cf4bf3aa312e59bb191b4be543b39f25e2

USER root
RUN chmod -R 755 /opt/java/openjdk \
    && apk add --no-cache curl=8.14.1-r2

USER 10001
VOLUME /tmp

EXPOSE 8080
COPY --chown=10001:0 target/qip-engine-*-exec.jar /app/qip-engine.jar
CMD ["/opt/java/openjdk/bin/java", "-Xmx832m", "-Djava.security.egd=file:/dev/./urandom", "-Dfile.encoding=UTF-8", "-jar", "/app/qip-engine.jar"]
