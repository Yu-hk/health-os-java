FROM eclipse-temurin:17-jre

WORKDIR /app
COPY target/health-os-java-0.1.0.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 18100

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
