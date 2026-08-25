FROM eclipse-temurin:21-jre-alpine
WORKDIR /application

# 安装时区、字体等基础依赖
RUN apk add --no-cache tzdata ca-certificates fontconfig freetype \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

# 创建非 root 用户
RUN addgroup -S spring && adduser -S spring -G spring

# 复制 Fat JAR
COPY --chown=spring:spring target/stock-calculator-service-*.jar /application/stock-calculator-service.jar

USER spring:spring

EXPOSE 18080

ENTRYPOINT ["java", "-jar", "/application/stock-calculator-service.jar"]
