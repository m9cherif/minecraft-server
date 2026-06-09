FROM eclipse-temurin:25-jdk

WORKDIR /server

RUN apt-get update && apt-get install -y curl python3

COPY start.sh .
RUN chmod +x start.sh

CMD ["./start.sh"]
