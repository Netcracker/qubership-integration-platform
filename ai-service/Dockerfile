# QIP AI Service — JVM (Quarkus fast-jar). Build artifacts first: mvn -DskipTests package
# Same layout as src/main/docker/Dockerfile.jvm; port 8080 to align with other QIP containers.
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24

ENV LANGUAGE='en_US:en'
ENV QUARKUS_HTTP_PORT=8080
ENV QUARKUS_HTTP_HOST=0.0.0.0

COPY --chown=185 target/quarkus-app/lib/ /deployments/lib/
COPY --chown=185 target/quarkus-app/*.jar /deployments/
COPY --chown=185 target/quarkus-app/app/ /deployments/app/
COPY --chown=185 target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 185
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Dquarkus.http.port=8080 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"

ENTRYPOINT [ "/opt/jboss/container/java/run/run-java.sh" ]
