FROM alpine/java:21-jdk

USER root
RUN ["chmod", "-R", "755", "/opt/java/openjdk"]
RUN apk add --no-cache curl

USER 10001
VOLUME /tmp

EXPOSE 8080

#CMD ["/opt/java/openjdk/bin/java", "-Xmx832m", "-Djava.security.egd=file:/dev/./urandom", "-Dfile.encoding=UTF-8", "-jar", "/app/qip-engine.jar"]

# We make four distinct layers so if there are application changes the library layers can be re-used
COPY --chown=10001 target/quarkus-app/lib/ /deployments/lib/
COPY --chown=10001 target/quarkus-app/*.jar /deployments/
COPY --chown=10001 target/quarkus-app/app/ /deployments/app/
COPY --chown=10001 target/quarkus-app/quarkus/ /deployments/quarkus/

#ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
#ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"

CMD ["/opt/java/openjdk/bin/java", "-jar", "/deployments/quarkus-run.jar"]
