# The platform + its Obens integration, on http://localhost:3000 (see docker-compose.yml)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# 1. The SDK. JitPack cannot build it yet (its default JDK is 8, the SDK needs 17), so build the git tag here and
#    install it under the coordinates the pom asks for. Once JitPack builds it, these two lines can go.
ARG SDK_VERSION=v0.1.3
RUN git clone --depth 1 --branch ${SDK_VERSION} https://github.com/Obens-Ltd/obens-sdk-java /sdk \
 && cd /sdk \
 && sed -i '0,/<groupId>so.obens<\/groupId>/s//<groupId>com.github.Obens-Ltd<\/groupId>/; 0,/<artifactId>obens-sdk<\/artifactId>/s//<artifactId>obens-sdk-java<\/artifactId>/; 0,/<version>[^<]*<\/version>/s//<version>'${SDK_VERSION}'<\/version>/' pom.xml \
 && mvn -q -B install -DskipTests -Dgpg.skip -Dmaven.javadoc.skip=true

# 2. The platform
COPY pom.xml .
COPY src src
RUN mvn -q -B -DskipTests compile dependency:copy-dependencies -DoutputDirectory=target/lib

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/classes target/classes
COPY --from=build /app/target/lib target/lib
COPY data data
COPY public public
# the SQLite file lives in /data: mount a volume there to keep your changes between runs
ENV PORT=3000 DB_PATH=/data/platform.db
RUN mkdir -p /data
VOLUME /data
EXPOSE 3000
CMD ["java", "-cp", "target/classes:target/lib/*", "so.obens.demo.App"]
