FROM alpine/java:21-jdk

USER root
RUN chmod -R 755 /opt/java/openjdk

VOLUME /tmp

USER 10001

EXPOSE 8080

# We make four distinct layers so if there are application changes the library layers can be re-used
COPY --chown=10001 target/quarkus-app/lib/ /app/lib/
COPY --chown=10001 target/quarkus-app/*.jar /app/
COPY --chown=10001 target/quarkus-app/app/ /app/app/
COPY --chown=10001 target/quarkus-app/quarkus/ /app/quarkus/

WORKDIR /app

# The heap takes half of the pod memory limit, leaving room for about 400 MiB off-heap; runtime-catalog defaults the limit to 1280Mi.
CMD ["/opt/java/openjdk/bin/java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=50", "-XX:MaxMetaspaceSize=256m", "-XX:MaxDirectMemorySize=128m", "-XX:+ExitOnOutOfMemoryError", "-Djava.security.egd=file:/dev/./urandom", "-Dfile.encoding=UTF-8", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
