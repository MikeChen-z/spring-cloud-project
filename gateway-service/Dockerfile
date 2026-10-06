# 基础镜像：JDK 17 运行时（Spring Boot 3.x 要求 JDK 17+）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/gateway-service-1.0.0.jar app.jar
EXPOSE 9000
ENV TZ=Asia/Shanghai
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-jar", "app.jar"]
