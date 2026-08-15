import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Scanner;

/**
 * "Cost price recovery" calculator.
 *
 * Strategy: after buying N shares at a given price, once the price rises by a
 * target percentage, sell only as many shares as needed so the sale proceeds
 * equal your original total investment. The remaining shares are then held
 * at zero net cost ("free shares").
 *
 * Example: buy 100 shares at $100 (cost = $10,000). Price rises 11% to $111.
 * Selling ~90.09 shares at $111 returns the full $10,000, leaving ~9.91
 * shares held for free.
 */
public class TradingCostPriceCalculator {

    public static void main(String[] args) {
        if (args.length == 3) {
            BigDecimal buyPrice = new BigDecimal(args[0]);
            BigDecimal quantity = new BigDecimal(args[1]);
            BigDecimal gainPercent = new BigDecimal(args[2]);
            printResult(buyPrice, quantity, gainPercent);
            return;
        }

        Scanner scanner = new Scanner(System.in);
        System.out.print("Buy price per share: ");
        BigDecimal buyPrice = new BigDecimal(scanner.nextLine().trim());
        System.out.print("Quantity bought: ");
        BigDecimal quantity = new BigDecimal(scanner.nextLine().trim());
        System.out.print("Target gain percent (e.g. 11 for 11%): ");
        BigDecimal gainPercent = new BigDecimal(scanner.nextLine().trim());
        scanner.close();

        printResult(buyPrice, quantity, gainPercent);
    }

    private static void printResult(BigDecimal buyPrice, BigDecimal quantity, BigDecimal gainPercent) {
        Result result = calculate(buyPrice, quantity, gainPercent);

        System.out.println();
        System.out.println("Total investment      : " + money(result.totalCost));
        System.out.println("Sell price at +" + gainPercent + "%   : " + money(result.sellPrice));
        System.out.println("Shares to sell (exact) : " + result.sharesToSellExact);
        System.out.println("Shares to sell (round) : " + result.sharesToSellRounded);
        System.out.println("Free shares remaining  : " + result.freeSharesRemaining);
        System.out.println("Proceeds from that sale: " + money(result.actualProceeds));
    }

    public static Result calculate(BigDecimal buyPrice, BigDecimal quantity, BigDecimal gainPercent) {
        BigDecimal totalCost = buyPrice.multiply(quantity);

        BigDecimal gainMultiplier = BigDecimal.ONE.add(
                gainPercent.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        BigDecimal sellPrice = buyPrice.multiply(gainMultiplier).setScale(4, RoundingMode.HALF_UP);

        // Exact fractional number of shares whose proceeds exactly cover the cost.
        BigDecimal sharesToSellExact = totalCost.divide(sellPrice, 4, RoundingMode.HALF_UP);

        // Round up to whole shares so the sale fully recovers cost even if the
        // broker doesn't support fractional shares.
        BigDecimal sharesToSellRounded = totalCost.divide(sellPrice, 0, RoundingMode.CEILING);
        if (sharesToSellRounded.compareTo(quantity) > 0) {
            sharesToSellRounded = quantity;
        }

        BigDecimal freeSharesRemaining = quantity.subtract(sharesToSellRounded);
        BigDecimal actualProceeds = sellPrice.multiply(sharesToSellRounded).setScale(2, RoundingMode.HALF_UP);

        return new Result(totalCost, sellPrice, sharesToSellExact, sharesToSellRounded,
                freeSharesRemaining, actualProceeds);
    }

    private static String money(BigDecimal value) {
        return "$" + value.setScale(2, RoundingMode.HALF_UP);
    }

    public static class Result {
        public final BigDecimal totalCost;
        public final BigDecimal sellPrice;
        public final BigDecimal sharesToSellExact;
        public final BigDecimal sharesToSellRounded;
        public final BigDecimal freeSharesRemaining;
        public final BigDecimal actualProceeds;

        public Result(BigDecimal totalCost, BigDecimal sellPrice, BigDecimal sharesToSellExact,
                      BigDecimal sharesToSellRounded, BigDecimal freeSharesRemaining, BigDecimal actualProceeds) {
            this.totalCost = totalCost;
            this.sellPrice = sellPrice;
            this.sharesToSellExact = sharesToSellExact;
            this.sharesToSellRounded = sharesToSellRounded;
            this.freeSharesRemaining = freeSharesRemaining;
            this.actualProceeds = actualProceeds;
        }
    }
}
