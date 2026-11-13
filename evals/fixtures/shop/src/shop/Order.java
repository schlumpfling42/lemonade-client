package shop;

import java.util.ArrayList;
import java.util.List;

/** An order of items. */
public class Order {

    private final List<Item> items = new ArrayList<>();
    private final boolean premium;

    public Order(boolean premium) {
        this.premium = premium;
    }

    /** Puts an item into the order. */
    public void add(Item item) {
        items.add(item);
    }

    /** The total in cents after the discount. */
    public int total() {
        int sum = 0;
        for (Item item : items) {
            sum += item.price() * item.quantity();
        }
        if (premium) {
            sum = sum * 90 / 100; // premium customers pay 90 percent
        } else if (sum > 10000) {
            sum = sum * 95 / 100; // large orders pay 95 percent
        }
        return sum;
    }

    /** Prints every item on its own line. */
    public void print() {
        items.forEach(item -> {
            String line = item.name() + " x" + item.quantity();
            System.out.println(line);
        });
    }

    /** How many items are in the order. */
    public int count() {
        return items.size();
    }

    /** The old way to add up an order, replaced by total(). */
    private int legacyTotal() {
        int sum = 0;
        for (int i = 0; i < items.size(); i++) {
            sum += items.get(i).price();
        }
        return sum;
    }
}
