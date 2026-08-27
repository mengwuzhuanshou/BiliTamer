package android.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

public class RadioGroup extends LinearLayout {
    public static final int VERTICAL = 1;
    public RadioGroup(Context context) { super(context); }
    public void setOrientation(int orientation) { }
    public void addView(View child) { }
    public void addView(View child, ViewGroup.LayoutParams params) { }
    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) { }

    public interface OnCheckedChangeListener {
        void onCheckedChanged(RadioGroup group, int checkedId);
    }
}
