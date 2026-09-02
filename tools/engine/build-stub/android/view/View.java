package android.view;

import android.content.Context;
import android.content.res.Resources;

public class View {
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;
    public static final int NO_ID = -1;

    public View(Context context) { }
    public void setPadding(int left, int top, int right, int bottom) { }
    public void setLayoutParams(ViewGroup.LayoutParams params) { }
    public void setOnClickListener(OnClickListener l) { }
    public void setVisibility(int visibility) { }
    public int getVisibility() { return VISIBLE; }
    public ViewParent getParent() { return null; }
    public View findViewById(int id) { return null; }
    public View findViewWithTag(Object tag) { return null; }
    public int getHeight() { return 0; }
    public int getWidth() { return 0; }
    public int getId() { return NO_ID; }
    public void getLocationOnScreen(int[] outLocation) { }
    public View getRootView() { return null; }
    public boolean dispatchTouchEvent(MotionEvent event) { return false; }
    public ViewGroup.LayoutParams getLayoutParams() { return null; }
    public Context getContext() { return null; }
    public Resources getResources() { return null; }
    public void setClickable(boolean clickable) { }
    public void setMinimumWidth(int minWidth) { }
    public void setBackground(android.graphics.drawable.Drawable background) { }
    public void setTag(Object tag) { }
    public Object getTag() { return null; }
    public boolean post(Runnable action) { return false; }
    public boolean postDelayed(Runnable action, long delayMillis) { return false; }
    public void setContentDescription(CharSequence contentDescription) { }
    public void addOnLayoutChangeListener(OnLayoutChangeListener listener) { }

    public interface OnClickListener { void onClick(View v); }
    public interface OnLayoutChangeListener {
        void onLayoutChange(View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom);
    }
}
