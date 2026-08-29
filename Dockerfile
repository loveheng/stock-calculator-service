# ==========================================
# 第一阶段：编译（Java 21，与 pom.xml 的 java.version 一致）
# 多模块构建：只拷贝 main 变体及其依赖（common + 父 POM），不拷贝 native 模块
# ==========================================
FROM bellsoft/liberica-openjdk-alpine:21 AS builder
WORKDIR /build

# 安装 maven
RUN apk add --no-cache maven

# 复制构建所需的 POM 与模块源码（多模块：父 POM + common + main）
COPY .mvn ./.mvn
COPY mvnw pom.xml ./
COPY stock-calculator-common ./stock-calculator-common
COPY stock-calculator-main ./stock-calculator-main

# 只构建 main 变体及其依赖（-pl stock-calculator-main -am），跳过单测
RUN mvn -B clean package -DskipTests -pl stock-calculator-main -am -Dfile.encoding=UTF-8

# ==========================================
# 第二阶段：极简生产运行时
# 注意：Spring Boot 4 已移除 jarmode=layertools，不再做分层解包，
#       直接拷贝并运行可执行 fat jar。
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

# 3. 拷贝可执行 jar（main 变体的 boot jar）
COPY --from=builder /build/stock-calculator-main/target/*.jar ./app.jar

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

# 注入 Gemini API Key 与数据库连接（main 变体含爬虫，需要 PostgreSQL）
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.ai.openai.api-key=${GEMINI_API_KEY:-dummy-gemini-key} -jar app.jar"]
