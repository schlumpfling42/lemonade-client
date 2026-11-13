package shop;

/** One line of an order: what, for how much (in cents) and how many. */
public record Item(String name, int price, int quantity) {
}
