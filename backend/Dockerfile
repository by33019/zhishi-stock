FROM maven:3.9.11-eclipse-temurin-17 AS build
ARG APP_MODULE=stock-backend
WORKDIR /workspace
COPY backend ./backend
RUN mvn -f backend/pom.xml -pl "${APP_MODULE}" -am -DskipTests package

FROM eclipse-temurin:17-jre
ARG APP_MODULE=stock-backend
WORKDIR /app
RUN useradd --system --uid 10001 stock
COPY --from=build /workspace/backend/${APP_MODULE}/target/${APP_MODULE}-*.jar /app/app.jar
USER stock
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
