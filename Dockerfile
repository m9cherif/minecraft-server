FROM eclipse-temurin:21-jre

WORKDIR /server

RUN apt-get update && apt-get install -y curl

COPY start.sh .

RUN chmod +x start.sh

EXPOSE 25565

CMD ["./start.sh"]
