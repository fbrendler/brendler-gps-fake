package androidx.core.os;
import android.os.Build;
/** Versão mínima de androidx.core.os.BuildCompat (usada pelo Google Play Services). */
public class BuildCompat {
    public static boolean isAtLeastN() { return Build.VERSION.SDK_INT >= 24; }
    public static boolean isAtLeastO() { return Build.VERSION.SDK_INT >= 26; }
    public static boolean isAtLeastP() { return Build.VERSION.SDK_INT >= 28; }
    public static boolean isAtLeastQ() { return Build.VERSION.SDK_INT >= 29; }
    public static boolean isAtLeastR() { return Build.VERSION.SDK_INT >= 30; }
    public static boolean isAtLeastS() { return Build.VERSION.SDK_INT >= 31; }
    public static boolean isAtLeastSv2() { return Build.VERSION.SDK_INT >= 32; }
    public static boolean isAtLeastT() { return Build.VERSION.SDK_INT >= 33; }
    public static boolean isAtLeastU() { return Build.VERSION.SDK_INT >= 34; }
    public static boolean isAtLeastV() { return Build.VERSION.SDK_INT >= 35; }
}
