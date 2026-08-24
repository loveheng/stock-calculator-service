FROM alpine:3.19
WORKDIR /application

# 安装基础运行依赖、时区、动态链接兼容层，以及 AWT/ImageIO 必需的底层图形库和字体
RUN apk add --no-cache \
    tzdata \
    ca-certificates \
    gcompat \
    libstdc++ \
    # ------ 新增 AWT 依赖开始 ------
    freetype \
    fontconfig \
    libxext \
    libxrender \
    libxtst \
    libxi \
    # ------ 新增 AWT 依赖结束 ------
    && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 2. 安全非 root 用户
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 3. 复制由 GitHub Actions 编译好的原生二进制文件
COPY --chown=spring:spring target/stock-calculator-service /application/stock-calculator-service

EXPOSE 8080

ENTRYPOINT ["/application/stock-calculator-service"]