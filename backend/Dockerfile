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
#    重试次数提到 5 次可以降低但**不能消除**——2026-09-22 实测仍然断在
#    spring-boot-test:jar:3.5.9（257KB 只收到 242KB）。
# 3) -Dmaven.test.skip=true 而不是 -DskipTests：后者只是不"运行"测试，
#    仍然会解析并下载整个 test 作用域（spring-boot-test、junit、mockito、assertj、
#    testcontainers、byte-buddy 9MB 等），而运行时镜像一个都用不到。
#    每减少一个需要下载的构件，就少一份中断概率——从根上缩小失败面，而不是靠重试硬扛。
# 4) --mount=type=cache 挂载 Maven 本地仓库，两点收益：
#    a. stock-api / stock-job / stock-ai-worker 三个镜像各自执行一次本步骤，
#       没有共享缓存时等于把整棵依赖树下载三遍，失败概率被放大三倍；
#       共享后只下载一遍。sharing=locked 是为了让 Maven 串行写入同一仓库
#       （并发写同一 local repo 会出现半截构件，且报错形态与网络中断难以区分）。
#    b. 失败的下载不会留在构建层里，但会留在缓存挂载里：
#       重试因此是**增量**的，每次重试都是净进展——与前端 npm 的做法一致。
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    set -eu; \
    for attempt in 1 2 3; do \
        echo "--- mvn package 尝试 ${attempt}/3（模块 ${APP_MODULE}）---"; \
        if mvn -s backend/settings.xml -f backend/pom.xml -pl "${APP_MODULE}" -am \
                -Dmaven.test.skip=true \
                -Dmaven.wagon.http.retryHandler.count=5 \
                -Dmaven.wagon.http.retryHandler.class=standard \
                -Dmaven.wagon.httpconnectionManager.ttlSeconds=60 \
                package; then \
            exit 0; \
        fi; \
        sleep 5; \
    done; \
    echo "mvn package 连续 3 次失败" >&2; exit 1

FROM eclipse-temurin:17-jre
ARG APP_MODULE=stock-backend
WORKDIR /app
RUN useradd --system --uid 10001 stock
COPY --from=build /workspace/backend/${APP_MODULE}/target/${APP_MODULE}-*.jar /app/app.jar
USER stock
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
