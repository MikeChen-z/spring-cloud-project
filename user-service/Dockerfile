# 基础镜像：JDK 17 运行时（Spring Boot 3.x 要求 JDK 17+）
# -jre 比 -jdk 小很多（约 200MB vs 600MB），运行 jar 不需要编译工具
FROM eclipse-temurin:17-jre

# 容器内工作目录
WORKDIR /app

# 把宿主机打包好的 jar 复制进容器，统一命名为 app.jar
# 这样镜像内路径固定，不用关心版本号
COPY target/user-service-1.0.0.jar app.jar

# 声明服务监听端口（文档作用，实际映射靠 -p）
EXPOSE 8081

# 时区设为东八区，否则日志时间比本地慢 8 小时
ENV TZ=Asia/Shanghai

# 启动命令：java -jar，顺带限制堆内存（学习机内存有限）
# ENTRYPOINT 的数组形式不会被 shell 解析，参数传递更可靠
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-jar", "app.jar"]
