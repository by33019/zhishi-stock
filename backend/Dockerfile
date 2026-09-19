FROM maven:3.9.11-eclipse-temurin-17 AS build
ARG APP_MODULE=stock-backend
WORKDIR /workspace
COPY backend ./backend
# 镜像构建的依赖下载配置，两点说明：
# 1) -s backend/settings.xml 走阿里云公共仓库：直连 Maven Central 在本项目部署网络下
#    速率仅 1.7 kB/s 且频繁返回连接失败。详见 backend/settings.xml。
# 2) wagon 重试：容器网络栈在高并发 HTTPS 下载下存在约 3% 的偶发连接中断
#    （实测并发 12 请求 30 个构件失败 1 个），而一次完整构建需拉取数百个构件，
#    累积失败率接近必然，表现为 "Premature end of Content-Length delimited message body"。
#    将重试次数提到 5 次后失败率降至可忽略量级。
RUN mvn -s backend/settings.xml -f backend/pom.xml -pl "${APP_MODULE}" -am -DskipTests \
        -Dmaven.wagon.http.retryHandler.count=5 \
        -Dmaven.wagon.http.retryHandler.class=standard \
        -Dmaven.wagon.httpconnectionManager.ttlSeconds=60 \
        package

FROM eclipse-temurin:17-jre
ARG APP_MODULE=stock-backend
WORKDIR /app
RUN useradd --system --uid 10001 stock
COPY --from=build /workspace/backend/${APP_MODULE}/target/${APP_MODULE}-*.jar /app/app.jar
USER stock
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
