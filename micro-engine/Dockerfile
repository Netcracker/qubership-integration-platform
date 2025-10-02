FROM maven:3.9.11-amazoncorretto-21-alpine AS build

WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -B -Dgpg.skip

FROM alpine/java:21-jdk

USER root
RUN ["chmod", "-R", "755", "/opt/java/openjdk"]
RUN apk add --no-cache curl

VOLUME /tmp

USER 10001

EXPOSE 8080

# We make four distinct layers so if there are application changes the library layers can be re-used
COPY --chown=10001 --from=build /app/target/quarkus-app/lib/ /app/lib/
COPY --chown=10001 --from=build /app/target/quarkus-app/*.jar /app/
COPY --chown=10001 --from=build /app/target/quarkus-app/app/ /app/app/
COPY --chown=10001 --from=build /app/target/quarkus-app/quarkus/ /app/quarkus/

WORKDIR /app

CMD ["/opt/java/openjdk/bin/java", "-Xmx832m", "-Djava.security.egd=file:/dev/./urandom", "-Dfile.encoding=UTF-8", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
