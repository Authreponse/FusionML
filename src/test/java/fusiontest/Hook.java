package fusiontest;

/** 注入的钩子。必须与被注入方法的"压栈序列"完全匹配（返回 void）。 */
public final class Hook {
    public static int computeHits = 0;
    public static int mixHits = 0;
    public static int lastA = -1;

    public static void onCompute(Target self, int a, int b) {
        computeHits++;
        lastA = a;
    }

    public static void onMix(long x, double y) {
        mixHits++;
    }
}
