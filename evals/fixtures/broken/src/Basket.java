/** A shopping basket of prices in cents. */
public class Basket {

    private final int[] prices;

    public Basket(int[] prices) {
        this.prices = prices;
    }

    /** How many items are in the basket. */
    public int count() {
        return prices.size();
    }

    /** The total price in cents. */
    public int total() {
        int sum = 0;
        for (int price : prices) {
            sum += price;
        }
        return sum;
    }
}
