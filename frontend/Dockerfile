FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
# 容器网络对境外大流量下载不稳定，实测两种失败形态：
#   直连 registry.npmjs.org   -> ECONNRESET（连接被重置）
#   走 registry.npmmirror.com -> EIDLETIMEOUT（tarball 所在 cdn.npmmirror.com 连接空闲挂死）
# 对策：国内镜像源 + --maxsockets 限制并发连接数 + 拉长超时 + 提高重试次数。
# 重试时不清理容器内 npm 缓存，已下载的包可增量复用，因此每次重试都是净进展。
# 该镜像为公开源，GitHub Actions 同样可访问。
RUN set -eu; \
    for attempt in 1 2 3; do \
        echo "--- npm ci 尝试 ${attempt}/3 ---"; \
        if npm ci --registry=https://registry.npmmirror.com --prefer-offline \
                  --maxsockets=5 \
                  --fetch-retries=5 \
                  --fetch-retry-mintimeout=10000 \
                  --fetch-retry-maxtimeout=120000 \
                  --fetch-timeout=600000; then \
            exit 0; \
        fi; \
        rm -rf node_modules; \
        sleep 5; \
    done; \
    echo "npm ci 连续 3 次失败" >&2; exit 1
COPY frontend ./
RUN npm run build

FROM nginx:1.29-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
