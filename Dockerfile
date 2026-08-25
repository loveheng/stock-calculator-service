# ==========================================
# 第一阶段：编译与分层提取（必须使用 OpenJDK）
# ==========================================
FROM bellsoft/liberica-openjdk-alpine:21 AS builder
WORKDIR /build

# 安装原生 maven，确保 PATH 绝对可用
RUN apk add --no-cache maven

# 1. 复制 pom.xml 与源码
COPY pom.xml .
COPY src ./src

# 2. 容器内编译打包（如果单测没做 Mock，建议先跳过单测，只验证编译和打包）
RUN mvn clean package -DskipTests -Dfile.encoding=UTF-8

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

# 2. 安全实践：非 root 用户运行
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 3. 按分层复制（从 /build/extracted 目录拷贝）
COPY --from=builder /build/extracted/dependencies/ ./
COPY --from=builder /build/extracted/spring-boot-loader/ ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/ ./

EXPOSE 18080

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

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.ai.gemini.api-key=${GEMINI_API_KEY:-dummy-gemini-key} -Dspring.ai.gemini.base-url=${GEMINI_BASE_URL:-https://generativelanguage.googleapis.com} -Dspring.ai.openai.api-key=${DEEPSEEK_API_KEY:-dummy-deepseek-key} -Dspring.ai.openai.base-url=${DEEPSEEK_BASE_URL:-https://api.deepseek.com} org.springframework.boot.loader.launch.JarLauncher"]