import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingCostPriceCalculatorTest {

    @Test
    void calculatesBasicCostRecovery() {
        TradingCostPriceCalculator.Result result = calculate("100", "100", "10");

        assertDecimalEquals("10000", result.totalCost);
        assertDecimalEquals("110.0000", result.sellPrice);
        assertDecimalEquals("90.9091", result.sharesToSellExact);
        assertDecimalEquals("91", result.sharesToSellRounded);
        assertDecimalEquals("9", result.freeSharesRemaining);
        assertDecimalEquals("10010.00", result.actualProceeds);
    }

    @Test
    void calculatesElevenPercentExample() {
        TradingCostPriceCalculator.Result result = calculate("100", "100", "11");

        assertDecimalEquals("111.0000", result.sellPrice);
        assertDecimalEquals("90.0901", result.sharesToSellExact);
        assertDecimalEquals("91", result.sharesToSellRounded);
        assertDecimalEquals("9", result.freeSharesRemaining);
        assertDecimalEquals("10101.00", result.actualProceeds);
    }

    @Test
    void roundsUpToWholeSharesToRecoverCost() {
        TradingCostPriceCalculator.Result result = calculate("10", "100", "20");

        assertDecimalEquals("1000", result.totalCost);
        assertDecimalEquals("12.0000", result.sellPrice);
        assertDecimalEquals("83.3333", result.sharesToSellExact);
        assertDecimalEquals("84", result.sharesToSellRounded);
        assertDecimalEquals("16", result.freeSharesRemaining);
        assertDecimalEquals("1008.00", result.actualProceeds);
    }

    @Test
    void supportsFractionalQuantity() {
        TradingCostPriceCalculator.Result result = calculate("50", "2.5", "25");

        assertDecimalEquals("125.0", result.totalCost);
        assertDecimalEquals("62.5000", result.sellPrice);
        assertDecimalEquals("2.0000", result.sharesToSellExact);
        assertDecimalEquals("2", result.sharesToSellRounded);
        assertDecimalEquals("0.5", result.freeSharesRemaining);
        assertDecimalEquals("125.00", result.actualProceeds);
    }

    @Test
    void capsWholeShareSaleAtAvailableQuantity() {
        TradingCostPriceCalculator.Result result = calculate("100", "1", "1");

        assertDecimalEquals("101.0000", result.sellPrice);
        assertDecimalEquals("0.9901", result.sharesToSellExact);
        assertDecimalEquals("1", result.sharesToSellRounded);
        assertDecimalEquals("0", result.freeSharesRemaining);
        assertDecimalEquals("101.00", result.actualProceeds);
    }

    @Test
    void handlesVerySmallSharePrice() {
        TradingCostPriceCalculator.Result result = calculate("0.01", "1000", "100");

        assertDecimalEquals("10.00", result.totalCost);
        assertDecimalEquals("0.0200", result.sellPrice);
        assertDecimalEquals("500.0000", result.sharesToSellExact);
        assertDecimalEquals("500", result.sharesToSellRounded);
        assertDecimalEquals("500", result.freeSharesRemaining);
        assertDecimalEquals("10.00", result.actualProceeds);
    }

    @Test
    void handlesLargePosition() {
        TradingCostPriceCalculator.Result result = calculate("123.45", "100000", "15");

        assertDecimalEquals("12345000.00", result.totalCost);
        assertDecimalEquals("141.9675", result.sellPrice);
        assertDecimalEquals("86956.5217", result.sharesToSellExact);
        assertDecimalEquals("86957", result.sharesToSellRounded);
        assertDecimalEquals("13043", result.freeSharesRemaining);
        assertDecimalEquals("12345067.90", result.actualProceeds);
    }

    private static TradingCostPriceCalculator.Result calculate(String buyPrice, String quantity, String gainPercent) {
        return TradingCostPriceCalculator.calculate(
                new BigDecimal(buyPrice),
                new BigDecimal(quantity),
                new BigDecimal(gainPercent));
    }

    private static void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "Expected " + expected + " but was " + actual);
    }
}
