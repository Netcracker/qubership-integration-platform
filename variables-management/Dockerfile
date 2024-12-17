FROM alpine/java:21-jdk

USER root
RUN ["chmod", "-R", "755", "/opt/java/openjdk"]
RUN apk add --no-cache curl

USER 10001
VOLUME /tmp

EXPOSE 8080
COPY --chown=10001:0 target/qip-variables-management-*-exec.jar /app/qip-variables-management.jar
CMD ["/opt/java/openjdk/bin/java", "-Xmx384m", "-Djava.security.egd=file:/dev/./urandom", "-Dfile.encoding=UTF-8", "-jar", "/app/qip-variables-management.jar"]
