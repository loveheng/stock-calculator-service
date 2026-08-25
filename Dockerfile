FROM ubuntu:22.04
WORKDIR /application

# 避免交互式弹窗，安装时区、AWT/ImageIO 核心字体和图形依赖库
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    tzdata \
    ca-certificates \
    libfreetype6 \
    fontconfig \
    fonts-dejavu-core \
    libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && ln -fs /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && dpkg-reconfigure --frontend noninteractive tzdata

# 创建非 root 用户
RUN groupadd -r spring && useradd -r -g spring spring

# 复制 Native 二进制文件并赋予权限
COPY --chown=spring:spring target/stock-calculator-service /application/stock-calculator-service
RUN chmod +x /application/stock-calculator-service

USER spring:spring

EXPOSE 18080

# 强制开启 headless 模式
ENTRYPOINT ["/application/stock-calculator-service", "-Djava.awt.headless=true"]