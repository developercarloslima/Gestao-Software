FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -Ptenant -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# ffprobe valida a continuidade real da trilha de vídeo antes de aceitar a vistoria.
# ffmpeg permanece disponível para compatibilidade com ferramentas legadas de vídeo.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app app \
    && chown -R app:app /app
COPY --from=build --chown=app:app /app/target/cotacao-api-1.0.0.jar /app/app.jar
COPY --chown=app:app entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=America/Maceio -Djava.awt.headless=true"
ENV SPRING_PROFILES_ACTIVE=tenant GS_TENANT_MODE=true
ENTRYPOINT ["/app/entrypoint.sh"]
