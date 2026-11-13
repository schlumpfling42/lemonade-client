/* A header comment before the package: not kept by the model. */
package net.rka.ai.fixture;

import module java.base;
import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.*;
import static java.lang.Math.abs;
import static java.lang.Math.*;
import static java.util.Map.Entry;

/**
 * A tag with elements of every kind.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Tags.class)
@interface Tag {
    int LIMIT = 10;

    String value() default "";

    int priority() default 0;

    String[] aliases() default {};

    Class<?> type() default Object.class;

    Level level() default Level.LOW;

    Info info() default @Info(name = "none");
}

@Documented
@Retention(RetentionPolicy.RUNTIME)
@interface Tags {
    Tag[] value();
}

@interface Info {
    String name();

    int[] numbers() default {1, 2, 3};
}

@Target(ElementType.TYPE_USE)
@interface TypeUse {
}

/** An enum with constants that have arguments, bodies and annotations, and a static initializer. */
enum Level implements Supplier<String> {
    LOW("l") {
        @Override
        String describe() {
            return "low";
        }
    },
    /** The middle one. */
    @Deprecated MEDIUM("m", 2) {
        @Override
        String describe() {
            return "medium";
        }
    },
    HIGH;

    static final Map<String, Level> BY_CODE = new HashMap<>();

    static {
        for (Level level : values()) {
            BY_CODE.put(level.code, level);
        }
    }

    private final String code;
    private final int weight;

    Level() {
        this("h", 3);
    }

    Level(String code) {
        this(code, 1);
    }

    Level(String code, int weight) {
        this.code = code;
        this.weight = weight;
    }

    String describe() {
        return name().toLowerCase();
    }

    @Override
    public String get() {
        return code + weight;
    }
}

/** A sealed hierarchy of records. */
sealed interface Shape permits Circle, Square, Group {
    double area();
}

record Circle(double radius) implements Shape {
    public double area() {
        return PI * radius * radius;
    }
}

record Square(double side) implements Shape {
    public double area() {
        return side * side;
    }
}

record Group(List<? extends Shape> parts, String... labels) implements Shape {
    Group {
        parts = List.copyOf(parts);
    }

    public double area() {
        double sum = 0;
        for (Shape part : parts) sum += part.area();
        return sum;
    }
}

/** An interface with constants, a default, a static and a private method and nested types. */
interface Visitor<R, X extends Throwable> {
    int DEFAULT_DEPTH = 3, MAX_DEPTH = 10;

    R visit(Shape shape) throws X;

    default R visitAll(List<? extends Shape> shapes, BinaryOperator<R> combine) throws X {
        R result = null;
        for (Shape shape : shapes) {
            R next = visit(shape);
            result = result == null ? next : combine.apply(result, next);
        }
        log("visited " + shapes.size());
        return result;
    }

    static <R> Visitor<R, RuntimeException> constant(R value) {
        return shape -> value;
    }

    private static void log(String message) {
        System.out.println(message);
    }

    interface Nested {
        void run();
    }

    enum Mode {
        EAGER, LAZY
    }
}

interface Combined extends Runnable, Supplier<String>, Closeable {
}

abstract class Base<X> implements Closeable {
    protected X value;

    protected Base(X value) {
        this.value = value;
    }

    abstract X make() throws IOException;

    @Override
    public void close() throws IOException {
    }
}

class FixtureException extends Exception {
    private static final long serialVersionUID = 1L;

    FixtureException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * The class with everything: generics with bounds, every modifier, initializers, constructors, methods, nested types,
 * and every kind of statement and literal in the bodies.
 *
 * @param <T> a number that can be compared with itself
 */
@Tag("main")
@Tag(value = "second", priority = 2, aliases = {"b", "two"}, type = String.class, level = Level.HIGH, info = @Info(name = "i", numbers = {4, 5}))
@SuppressWarnings({"unchecked", "rawtypes", "unused"})
public strictfp abstract sealed class EveryConstruct<T extends Number & Comparable<T>> extends Base<T>
        implements Serializable, Comparable<EveryConstruct<T>>
        permits EveryConstruct.Leaf, EveryConstruct.Branch {

    // ---- fields ----

    public static final int CONSTANT = 0x7FFF_FFFF;
    protected static final long BIG = 9_223_372_036_854_775_807L, NEG = -0b1010L;
    private static final double[] SAMPLES = {1.0, .5, 1e3, 1E-3, 0x1.8p1, 1_000.5, 3.14159d, Double.NaN};
    static final float TINY = 1.5f, HUGE = 0x1p4f;
    static final char[] CHARS = {'a', '\n', '\'', '"', '\\', 'A', '\101', '\0'};
    static final String ESCAPES = "tab\t quote\" back\\ nul\0 unicodeé octal\101 brace { } semicolon ; // not a comment";
    static final String BLOCK = """
            Text block with "quotes" and \""" triple quotes,
              an indented line, a backslash \\, a trailing space \s
            and a joined \
            line.
            """;
    private final transient List<@TypeUse String> names = new ArrayList<@TypeUse String>();
    private volatile boolean running;
    int plain, array[] = {1, 2}, matrix[][] = new int[2][3];
    final Map<String, ? extends List<? super Integer>> wild = new HashMap<>();
    @Tag("field") @Deprecated public static Object mixed;
    java.lang.@TypeUse String qualified;
    Function<? super T, ? extends Comparable<?>>[] functions;
    static int counter;
    int octal = 0777, hex = 0xCAFE_BABE >>> 4, binary = 0B1010_1010, shifted = 1 << 3 >> 1 >>> 1;
    boolean flag = !running && (counter > 0 || counter < -1) ^ true;
    long mixedLiteral = 1L + 0xFFL + 07L;
    Comparator<String> byLength = (a, b) -> Integer.compare(a.length(), b.length());
    Map<String, List<Map<Integer, Set<String>>>> deep = new HashMap<String, List<Map<Integer, Set<String>>>>();

    // ---- initializers ----

    static {
        counter = 1;
    }

    /** An instance initializer. */
    {
        running = false;
        names.add("init");
    }

    static {
        counter++;
    }

    // ---- constructors ----

    /** The main constructor. */
    @Tag("ctor")
    protected EveryConstruct(final T value, int... extra) throws IllegalArgumentException {
        super(value);
        if (extra.length > 0) {
            names.add("extra" + extra.length);
        }
    }

    <Z extends CharSequence> EveryConstruct(Z text, T value) {
        this(value);
        names.add(text.toString());
    }

    private EveryConstruct() {
        this(null);
    }

    // ---- methods ----

    @Override
    public abstract T make() throws IOException;

    @Override
    public int compareTo(EveryConstruct<T> other) {
        return value.compareTo(other.value);
    }

    public synchronized final <R extends Comparable<? super R>, S> Map<R, List<S>> group(Collection<? extends S> items,
            Function<? super S, ? extends R> key) throws FixtureException {
        Map<R, List<S>> groups = new TreeMap<>();
        try {
            for (S item : items) {
                groups.computeIfAbsent(key.apply(item), k -> new ArrayList<>()).add(item);
            }
        } catch (RuntimeException e) {
            throw new FixtureException("grouping failed", e);
        }
        return groups;
    }

    public static <E extends Enum<E> & Supplier<String>> String joined(Class<E> type, String... separators) {
        StringBuilder out = new StringBuilder();
        for (E constant : type.getEnumConstants()) {
            out.append(constant.get()).append(separators.length > 0 ? separators[0] : "");
        }
        return out.toString();
    }

    @SafeVarargs
    static <Q> List<Q> listOf(Q... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    static <A> void swap(A[] array, int i, int j) {
        A tmp = array[i];
        array[i] = array[j];
        array[j] = tmp;
    }

    native void nativeCall(int a, long b);

    int legacy(int x)[] {
        return new int[] {x};
    }

    void receiver(EveryConstruct<T> this, int x) {
    }

    protected void finalParams(final int a, @Tag("p") final String b, @TypeUse Object c) {
    }

    <@TypeUse Y> void typed(@TypeUse Y y) {
    }

    public final void locked() {
        synchronized (this) {
            counter++;
        }
    }

    Entry<String, Integer> entry() {
        return Map.entry("k", abs(-1));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + names;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, names);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EveryConstruct<?> that && Objects.equals(value, that.value);
    }

    /** Every kind of statement, expression and literal. */
    @SuppressWarnings("deprecation")
    int statements(int n, Object o, Shape shape) throws Exception {
        int total = 0;
        outer:
        for (int i = 0, j = 10; i < n && j > 0; i++, j -= 2) {
            for (int k : new int[] {1, 2, 3}) {
                if (k == 2) continue;
                if (i == 3) continue outer;
                if (i == 7) break outer;
                total += k * i - j / (k + 1) % 5;
            }
        }
        int w = 0;
        while (w < 3) w++;
        do {
            w--;
        } while (w > 0);
        switch (n) {
            case 0:
            case 1:
                total++;
                break;
            case 2: {
                total--;
            }
            default:
                total += 2;
        }
        String s = switch (n) {
            case 0, 1 -> "low";
            case 2 -> {
                String t = "mid";
                yield t;
            }
            default -> "high";
        };
        double area = switch (shape) {
            case Circle(double r) when r > 100 -> -1;
            case Circle c -> c.area();
            case Square(var side) -> side * side;
            case Group(var parts, var labels) -> parts.size() + labels.length;
        };
        if (o instanceof String str && !str.isEmpty()) total += str.length();
        else if (!(o instanceof Integer count)) total--;
        else total += count;
        Object[] objects = {1, "two", 3.0, null, 'c', 5L, new int[0]};
        for (Object x : objects) {
            if (x instanceof Number num) total += num.intValue();
        }
        for (var _ : objects) {
            total++;
        }
        var list = new ArrayList<Map<String, ? extends Number>>();
        final var map = new HashMap<String, List<Integer>>();
        Runnable r = () -> System.out.println("run");
        Supplier<List<String>> sup = ArrayList::new;
        BiFunction<String, Integer, Character> charAt = String::charAt;
        Function<Integer, int[]> arr = int[]::new;
        Comparator<String> cmp = Comparator.<String, Integer>comparing(String::length).thenComparing(Comparator.naturalOrder());
        IntBinaryOperator op = (int a, int b) -> a + b;
        BinaryOperator<Integer> op2 = (var a, var b) -> a * b;
        BiFunction<Integer, Integer, Integer> ignore = (_, _) -> 0;
        Function<Integer, Function<Integer, Integer>> curried = a -> b -> a + b;
        Supplier<Supplier<String>> nested = () -> () -> "deep";
        Object anon = new Object() {
            int count = 1;

            @Override
            public String toString() {
                return "anon" + count;
            }
        };
        Comparator<Integer> anonCmp = new Comparator<>() {
            public int compare(Integer a, Integer b) {
                return b - a;
            }
        };
        List<String> doubleBrace = new ArrayList<String>() {{
            add("x");
        }};
        class Local implements Comparable<Local> {
            int id;

            Local(int id) {
                this.id = id;
            }

            public int compareTo(Local other) {
                return Integer.compare(id, other.id);
            }
        }
        record LocalRecord(int a, String b) {
            static int twice(int x) {
                return 2 * x;
            }
        }
        enum LocalEnum {
            ONE, TWO
        }
        interface LocalInterface {
            int apply(int x);
        }
        LocalInterface li = x -> x + 1;
        try (Closeable c1 = () -> {
        }; var c2 = new Leaf("x")) {
            total += li.apply(LocalRecord.twice(2));
        } catch (IOException | IllegalStateException e) {
            throw new FixtureException("multi", e);
        } catch (final Exception e) {
            throw e;
        } finally {
            total++;
        }
        try {
            total--;
        } catch (RuntimeException _) {
        }
        synchronized (this) {
            assert total >= 0 : "negative total " + total;
        }
        int[][] grid = new int[3][];
        grid[0] = new int[] {1, 2};
        int[][][] cube = {{{1}}, {{2, 3}, {4}}};
        long big = (long) total << 40 | (total & 0xFF) ^ ~total;
        total += (int) (big >>> 3);
        total <<= 1;
        total >>= 1;
        total >>>= 1;
        total *= 2;
        total /= 2;
        total %= 100;
        total &= 0xFFFF;
        total |= 1;
        total ^= 2;
        total -= 1;
        boolean b = total > 0 ? n < 5 : n >= 5 || !(o == null);
        int ternary = n > 0 ? n > 10 ? 2 : 1 : 0;
        char ch = 'a';
        ch += 1;
        ch++;
        total = +total + -total - (-1) + (+1);
        int post = total++ + ++total - total-- - --total;
        Object cast = (Comparable<String> & Serializable) "text";
        String joinedNames = String.join(",", this.<String>listOf("a", "b"));
        EveryConstruct<T>.Inner inner = this.new Inner();
        ;
        return total + (int) area + s.length() + list.size() + map.size() + joinedNames.length() + inner.depth() + post;
    }

    // ---- nested types ----

    /** An inner class. */
    public class Inner {
        int depth() {
            return EveryConstruct.this.names.size();
        }
    }

    public static final class Leaf extends EveryConstruct<Integer> {
        private final String label;

        public Leaf(String label) {
            int length = Objects.requireNonNull(label).length();
            super(length);
            this.label = label;
        }

        @Override
        public Integer make() {
            return value;
        }
    }

    public static non-sealed class Branch<N extends Number & Comparable<N>> extends EveryConstruct<N> {
        public Branch(N value) {
            super(value);
        }

        @Override
        public N make() throws IOException {
            return value;
        }
    }

    protected interface Hook {
        void on(String event);
    }

    private enum State {
        NEW,
        RUNNING {
            @Override
            State next() {
                return NEW;
            }
        };

        State next() {
            return RUNNING;
        }
    }

    abstract static class Template<A, B> {
        abstract B apply(A a);
    }

    @interface Marker {
    }

    record Pair<A, B>(A first, B second) {
        Pair {
            Objects.requireNonNull(first);
        }

        Pair(A only) {
            this(only, null);
        }

        static <A> Pair<A, A> twin(A a) {
            return new Pair<>(a, a);
        }
    }

    record Range(int low, int high) {
        Range(int low, int high) {
            if (low > high) throw new IllegalArgumentException(low + ">" + high);
            this.low = low;
            this.high = high;
        }
    }

    public static void main(String... args) throws Exception {
        Leaf leaf = new Leaf("main");
        System.out.println(leaf.statements(args.length, leaf, new Circle(1)));
        System.out.println(Visitor.constant(1).visit(new Square(2)));
    }
}
