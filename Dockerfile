# syntax=docker/dockerfile:1
# Packages a jar built beforehand with `./gradlew bootJar` (the build needs Docker for jOOQ codegen
# and tests, so it runs on the host or in CI, not inside this image).

# --- Split the jar into layers so dependencies are cached separately from app code ---
FROM eclipse-temurin:25-jre AS extract
WORKDIR /workspace
COPY build/libs/portal-*-SNAPSHOT.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# --- Run ---
FROM eclipse-temurin:25-jre
RUN groupadd --system portal && useradd --system --gid portal portal
WORKDIR /app
COPY --from=extract /workspace/extracted/dependencies/ ./
COPY --from=extract /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract /workspace/extracted/application/ ./
USER portal
EXPOSE 8080
# The droplet is shared with Vaultwarden and Documenso, so the heap is capped (PRD: Hosting).
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:+ExitOnOutOfMemoryError"
ARG PORTAL_VERSION=dev
ENV PORTAL_VERSION=${PORTAL_VERSION}
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3"]
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
