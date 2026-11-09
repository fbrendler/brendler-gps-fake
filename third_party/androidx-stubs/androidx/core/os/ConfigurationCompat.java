package androidx.core.os;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;
public final class ConfigurationCompat {
    @SuppressWarnings("deprecation")
    public static LocaleListCompat getLocales(Configuration c) {
        if (Build.VERSION.SDK_INT >= 24) {
            LocaleList ll = c.getLocales();
            Locale[] a = new Locale[ll.size()];
            for (int i = 0; i < a.length; i++) a[i] = ll.get(i);
            return new LocaleListCompat(a);
        }
        return new LocaleListCompat(new Locale[]{c.locale});
    }
}
