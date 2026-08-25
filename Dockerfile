FROM ubuntu:22.04
WORKDIR /application

ENV DEBIAN_FRONTEND=noninteractive

# 1. 安装 AWT/ImageIO 所需的底层库、字体以及 JDK headless 动态链接库 (提供 libawt.so 等)
RUN apt-get update && apt-get install -y --no-install-recommends \
    tzdata \
    ca-certificates \
    libfreetype6 \
    fontconfig \
    fonts-dejavu-core \
    libstdc++6 \
    openjdk-21-jre-headless \
    && rm -rf /var/lib/apt/lists/* \
    && ln -fs /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && dpkg-reconfigure --frontend noninteractive tzdata

# 2. 设置动态链接库搜索路径，让 Native 可执行文件能找到 libawt.so, libawt_headless.so 等
ENV LD_LIBRARY_PATH="/usr/lib/jvm/java-21-openjdk-amd64/lib:/usr/lib/jvm/java-21-openjdk-amd64/lib/server:${LD_LIBRARY_PATH}"

# 3. 创建非 root 用户
RUN groupadd -r spring && useradd -r -g spring spring

# 4. 复制编译好的 Native 二进制文件
COPY --chown=spring:spring target/stock-calculator-service /application/stock-calculator-service
RUN chmod +x /application/stock-calculator-service

USER spring:spring

EXPOSE 18080

# 5. 启动参数中显式指定 headless 模式和 library.path
ENTRYPOINT ["/application/stock-calculator-service", "-Djava.awt.headless=true", "-Djava.library.path=/usr/lib/jvm/java-21-openjdk-amd64/lib"]