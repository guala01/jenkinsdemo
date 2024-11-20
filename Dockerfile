FROM openjdk:22-jdk-slim
WORKDIR /app
COPY build/libs/cloud-email-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
