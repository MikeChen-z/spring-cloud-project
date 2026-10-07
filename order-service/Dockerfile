FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/order-service-1.0.0.jar app.jar
EXPOSE 8082
ENV TZ=Asia/Shanghai
# order 是最重的服务（Seata RM + RocketMQ 事务消息 + Feign + 缓存），
# 堆给 384m 上限但初始只认领 128m；Metaspace 同样封顶
ENTRYPOINT ["java", "-Xms128m", "-Xmx384m", "-XX:MaxMetaspaceSize=256m", "-jar", "app.jar"]
