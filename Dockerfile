# ==============================================================================
# 第一阶段：GraalVM AOT 原生编译（耗时较长，消耗 CPU/内存完成静态分析）
# ==============================================================================
FROM container-registry.oracle.com/graalvm/native-image:21 AS builder
WORKDIR /build

# 安装原生 maven
RUN microdnf install -y maven gcc glibc-devel zlib-devel binutils

# 1. 复制依赖描述并缓存
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# 2. 复制源码并执行 Native 编译
COPY src ./src
RUN mvn -Pnative native:compile -DskipTests -Dfile.encoding=UTF-8

# ==============================================================================
# 第二阶段：极简生产运行时（完全没有 JRE，仅 30MB 左右）
# ==============================================================================
FROM alpine:3.19
WORKDIR /application

# 1. 基础依赖与时区
RUN apk add --no-cache tzdata ca-certificates && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 2. 安全非 root 用户
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 3. 仅复制生成的二进制可执行文件
COPY --from=builder --chown=spring:spring /build/target/stock-calculator-service /application/stock-calculator-service

EXPOSE 8080

# 4. 原生二进制直接运行，支持秒级启动与极低内存
ENTRYPOINT ["/application/app"]