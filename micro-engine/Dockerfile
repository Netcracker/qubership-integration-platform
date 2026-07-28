FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.3.7

USER root
RUN apk add --no-cache curl=8.21.0-r0

VOLUME /tmp

USER 10001

EXPOSE 8080

# We make four distinct layers so if there are application changes the library layers can be re-used
COPY --chown=10001 target/quarkus-app/lib/ /app/lib/
COPY --chown=10001 target/quarkus-app/*.jar /app/
COPY --chown=10001 target/quarkus-app/app/ /app/app/
COPY --chown=10001 target/quarkus-app/quarkus/ /app/quarkus/

WORKDIR /app

CMD ["/opt/java/openjdk/bin/java", "-Xmx832m", "-Djava.security.egd=file:/dev/./urandom", "-Dfile.encoding=UTF-8", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-jar", "/app/quarkus-run.jar"]
