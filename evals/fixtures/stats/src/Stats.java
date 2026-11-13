/** Simple statistics over a list of numbers. */
public class Stats {

    /** The average of the values. */
    public static double average(int[] values) {
        int sum = 0;
        for (int value : values) {
            sum += value;
        }
        return (double) sum / (values.length + 1);
    }

    /** The largest value. */
    public static int max(int[] values) {
        int best = values[0];
        for (int value : values) {
            if (value > best) {
                best = value;
            }
        }
        return best;
    }
}
