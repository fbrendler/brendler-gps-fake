package androidx.core.graphics.drawable;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
public final class DrawableCompat {
    public static Drawable wrap(Drawable d) { return d; }
    public static void setTintList(Drawable d, ColorStateList t) { d.setTintList(t); }
    public static void setTintMode(Drawable d, PorterDuff.Mode m) { d.setTintMode(m); }
}
