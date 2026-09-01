package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;

public abstract class Drawable {
    public abstract void draw(Canvas canvas);
    public abstract void setAlpha(int alpha);
    public abstract void setColorFilter(ColorFilter colorFilter);
    public abstract int getOpacity();
    public Rect getBounds() { return new Rect(); }
}
