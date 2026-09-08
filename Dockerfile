# --- Stage 1: Build Stage (ビルド環境) ---
# 使用官方 Maven 镜像进行编译，利用缓存机制加速构建
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# 1. 先只拷贝 pom.xml 下载依赖 (利用 Docker Layer Caching)
COPY pom.xml .
RUN mvn dependency:go-offline

# 2. 再拷贝源代码进行打包，并运行默认确定性测试
COPY src ./src
RUN mvn -B clean package

# --- Stage 2: Run Stage (実行環境) ---
# 使用轻量级的 JRE 镜像，减小体积
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache curl \
    && addgroup -S smartdoc \
    && adduser -S smartdoc -G smartdoc \
    && mkdir -p /app/data \
    && chown -R smartdoc:smartdoc /app

# 从构建阶段拷贝 jar 包
COPY --from=build --chown=smartdoc:smartdoc /app/target/*.jar app.jar

USER smartdoc

# 暴露端口 (假设你是 8080)
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
    CMD curl --fail --silent --show-error http://localhost:8080/actuator/health || exit 1

# 容器启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]
