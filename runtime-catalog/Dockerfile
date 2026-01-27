FROM alpine/java:21-jdk

USER root
RUN chmod -R 755 /opt/java/openjdk \
    && apk add --no-cache curl=8.14.1-r2

USER 10001
VOLUME /tmp

EXPOSE 8080
COPY --chown=10001:0 target/qip-runtime-catalog-*-exec.jar /app/qip-runtime-catalog.jar
CMD ["/opt/java/openjdk/bin/java", "-Xmx512m", "-Djava.security.egd=file:/dev/./urandom", "-Dfile.encoding=UTF-8", "-jar", "/app/qip-runtime-catalog.jar"]
