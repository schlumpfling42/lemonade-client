public class Main {

    public static void main(String[] args) {
        Basket basket = new Basket(new int[] {250, 175, 90});
        System.out.println(basket.count() + " items, " + basket.total() + " cents");
    }
}
