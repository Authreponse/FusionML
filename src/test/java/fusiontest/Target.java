package fusiontest;

public class Target {
    public static int calls = 0;

    public int compute(int a, int b) {
        int sum = 0;
        for (int i = 0; i < a; i++) {
            if (i % 2 == 0) {
                sum += b;
            } else {
                sum += b * 2;
            }
        }
        return sum;
    }

    public static long mix(long x, double y) {
        long acc = x;
        for (int i = 0; i < 4; i++) {
            acc += (long) (y * i);
        }
        return acc;
    }
}
