package androidx.core.os;
import java.util.Locale;
public final class LocaleListCompat {
    private final Locale[] list;
    LocaleListCompat(Locale[] l) { list = l; }
    public Locale get(int i) { return i >= 0 && i < list.length ? list[i] : null; }
    public int size() { return list.length; }
    public boolean isEmpty() { return list.length == 0; }
}
