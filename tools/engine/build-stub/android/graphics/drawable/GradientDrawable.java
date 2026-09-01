package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.ColorFilter;

public class GradientDrawable extends Drawable {
    public void setColor(int color) { }
    public void setCornerRadius(float radius) { }
    public void setStroke(int width, int color) { }
    @Override public void draw(Canvas canvas) { }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter colorFilter) { }
    @Override public int getOpacity() { return 0; }
}
