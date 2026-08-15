FROM eclipse-temurin:17-jdk

WORKDIR /app
COPY . .

RUN javac TradingCostPriceCalculator.java TradingWiseServer.java

EXPOSE 8080
CMD ["java", "TradingWiseServer"]
