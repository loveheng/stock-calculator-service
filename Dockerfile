# ==========================================
# 第一阶段：分层提取（Builder）
# ==========================================
FROM bellsoft/liberica-openjre-alpine:21 AS builder
WORKDIR /build

# 安装原生 maven，确保 PATH 绝对可用
RUN apk add --no-cache maven

# 1. 单独复制 pom.xml 下载依赖，最大化复用 Docker 缓存层
COPY pom.xml .

# 2. 复制源码
COPY src ./src

# 3. 容器内编译打包（代码报错或单测失败会在此中断）
RUN mvn clean package -DskipTests=false -Dfile.encoding=UTF-8

# 3. 提取 Spring Boot layertools 分层文件
WORKDIR /build/extracted
RUN java -Djarmode=layertools -jar /build/target/*.jar extract

# ==========================================
# 第二阶段：极简生产运行时（Runtime，~120MB）
# ==========================================
FROM bellsoft/liberica-openjre-alpine:21
WORKDIR /application

# 1. 基础系统时区配置
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# ==============================================================================
# 【备用扩展区】：如后续需要本地 AWT 图形高级渲染/复杂字体，取消下面一行的注释即可
# RUN apk add --no-cache fontconfig ttf-dejavu
# ==============================================================================

# 2. 安全实践：非 root 用户运行
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 3. 按分层复制（最大化复用 Podman/Docker 缓存层）
COPY --from=builder /application/dependencies/ ./
COPY --from=builder /application/spring-boot-loader/ ./
COPY --from=builder /application/snapshot-dependencies/ ./
COPY --from=builder /application/application/ ./

EXPOSE 8080

# 预留可配置的环境变量
ENV GEMINI_API_KEY="" \
    GEMINI_BASE_URL="https://generativelanguage.googleapis.com"

# 4. 针对 512MB 内存环境深度优化的 JVM 参数
ENV JAVA_OPTS="-XX:+UseSerialGC \
               -Xms96m \
               -Xmx200m \
               -XX:MaxMetaspaceSize=128m \
               -XX:CompressedClassSpaceSize=32m \
               -XX:ReservedCodeCacheSize=48m \
               -XX:TieredStopAtLevel=1 \
               -Xss512k \
               -Djava.awt.headless=true \
               -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.ai.gemini.api-key=$GEMINI_API_KEY -Dspring.ai.gemini.base-url=$GEMINI_BASE_URL org.springframework.boot.loader.launch.JarLauncher"]