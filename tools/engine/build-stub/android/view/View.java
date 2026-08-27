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
    public Context getContext() { return null; }
    public Resources getResources() { return null; }
    public interface OnClickListener { void onClick(View v); }
}
