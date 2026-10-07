# 基础镜像：JDK 17 运行时（Spring Boot 3.x 要求 JDK 17+）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/gateway-service-1.0.0.jar app.jar
EXPOSE 9000
ENV TZ=Asia/Shanghai
# 网关只做路由转发，不承载业务数据，堆 256m 足够
ENTRYPOINT ["java", "-Xms128m", "-Xmx256m", "-XX:MaxMetaspaceSize=256m", "-jar", "app.jar"]
