FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/order-service-1.0.0.jar app.jar
EXPOSE 8082
ENV TZ=Asia/Shanghai
ENTRYPOINT ["java", "-Xms512m", "-Xmx512m", "-jar", "app.jar"]
