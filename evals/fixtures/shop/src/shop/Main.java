package shop;

public class Main {

    public static void main(String[] args) {
        Order order = new Order(true);
        order.add(new Item("pen", 150, 4));
        order.add(new Item("book", 1200, 1));
        order.print();
        System.out.println("total " + order.total());
        System.out.println("count " + order.count());
        System.out.println("shipping " + Shipping.cost(Speed.STANDARD));
    }
}
