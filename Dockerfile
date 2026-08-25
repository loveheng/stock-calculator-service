# ============================================================================
# JVM 模式 Dockerfile（备选方案）
# 优先使用 Native 镜像（Dockerfile.native），此文件仅作为 JVM 回退方案。
# 构建命令: docker build -t stock-calculator:jvm -f Dockerfile .
# ============================================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /application

# 安装时区、字体等基础依赖
RUN apk add --no-cache tzdata ca-certificates fontconfig freetype curl \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

# 创建非 root 用户
RUN addgroup -S spring && adduser -S spring -G spring

# 复制 Fat JAR
COPY --chown=spring:spring target/stock-calculator-service-*.jar /application/stock-calculator-service.jar

USER spring:spring

EXPOSE 18080

# 健康检查：服务未引入 actuator，因此直接探测 HTTP 端口是否响应
#   - curl -s 忽略 HTTP 状态码（即使返回 404 也说明服务已启动）
#   - 实际业务端口 18080
HEALTHCHECK --start-period=30s --timeout=5s --interval=30s --retries=3 \
    CMD curl -s http://localhost:18080/ > /dev/null 2>&1 || exit 1

ENTRYPOINT ["java", "-jar", "/application/stock-calculator-service.jar"]