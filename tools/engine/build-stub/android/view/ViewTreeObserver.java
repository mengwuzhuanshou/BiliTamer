package android.view;

public class ViewTreeObserver {
public void addOnGlobalLayoutListener(OnGlobalLayoutListener listener) { }
public void removeOnGlobalLayoutListener(OnGlobalLayoutListener listener) { }
public interface OnGlobalLayoutListener { void onGlobalLayout(); }
}
