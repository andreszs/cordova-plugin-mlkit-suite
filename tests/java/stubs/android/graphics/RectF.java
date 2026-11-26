package android.graphics;

/** Minimal JVM stand-in so FaceCaptureModels compiles in plain-Java unit tests. */
public class RectF {
    public float left;
    public float top;
    public float right;
    public float bottom;

    public RectF() { }

    public RectF(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public RectF(RectF source) {
        this(source.left, source.top, source.right, source.bottom);
    }

    public final float width() { return right - left; }
    public final float height() { return bottom - top; }
    public final float centerX() { return (left + right) * 0.5f; }
    public final float centerY() { return (top + bottom) * 0.5f; }
}
