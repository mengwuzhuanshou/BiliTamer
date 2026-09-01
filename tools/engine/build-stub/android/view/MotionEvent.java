package android.view;

public final class MotionEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;

    public static MotionEvent obtain(long downTime, long eventTime, int action,
                                     float x, float y, int metaState) { return null; }
    public void recycle() { }
}
