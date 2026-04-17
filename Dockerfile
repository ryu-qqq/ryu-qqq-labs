FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/*.jar app.jar

# JVM 옵션을 외부에서 주입할 수 있도록
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
