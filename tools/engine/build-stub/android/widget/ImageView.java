package android.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;

public class ImageView extends View {
    public ImageView(Context context) { super(context); }
    public void setImageDrawable(Drawable drawable) { }
    public void setScaleType(ScaleType scaleType) { }

    public enum ScaleType { CENTER, CENTER_CROP, CENTER_INSIDE, FIT_CENTER, FIT_XY, MATRIX }
}
