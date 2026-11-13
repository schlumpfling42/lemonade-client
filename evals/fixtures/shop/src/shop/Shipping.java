package shop;

/** What shipping costs. */
public class Shipping {

    /** The cost in cents. */
    public static int cost(Speed speed) {
        switch (speed) {
            case STANDARD:
                return 500;
            case EXPRESS:
                return 1200;
            default:
                throw new IllegalArgumentException("unknown speed " + speed);
        }
    }
}
