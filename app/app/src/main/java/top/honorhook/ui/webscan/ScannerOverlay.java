package top.honorhook.ui.webscan;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 科技感取景框：暗色遮罩 + 渐辉圆角框 + 四角亮标 + 往复扫描线 */
public class ScannerOverlay extends View {

    // 极简白（2026-11-03）：浅遮罩 + 纯黑角标/扫描线，去渐变去辉光
    private static final int ACCENT = 0xFF17171A;
    private static final int MASK = 0x40000000;

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path framePath = new Path();
    private final RectF frame = new RectF();

    private ValueAnimator animator;
    private float fraction;
    private final android.graphics.RectF frameRect = new android.graphics.RectF();

    /** 当前取景框在本 View 坐标系内的矩形（供解码裁剪对齐） */
    public android.graphics.RectF getFrameRect() {
        return frameRect;
    }

    public ScannerOverlay(Context c) {
        super(c);
        maskPaint.setColor(MASK);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        cornerPaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        setLayerType(LAYER_TYPE_SOFTWARE, null); // 阴影/模糊需要
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2400);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            fraction = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float side = Math.min(w, h) * 0.66f;
        float cx = w / 2f, cy = h / 2f - h * 0.06f;
        frame.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2);
        frameRect.set(frame);
        float r = side * 0.09f;
        framePath.reset();
        framePath.addRoundRect(frame, r, r, Path.Direction.CW);

        // ① 半透明遮罩，取景框内镂空
        int sc = canvas.saveLayer(0, 0, w, h, null);
        canvas.drawRect(0, 0, w, h, maskPaint);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        canvas.drawPath(framePath, maskPaint);
        maskPaint.setXfermode(null);
        canvas.restoreToCount(sc);

        // ② 取景框细描边
        Paint faint = new Paint(cornerPaint);
        faint.setStrokeWidth(dp(1));
        faint.setColor(0x1A000000);
        canvas.drawPath(framePath, faint);

        // ③ 四角标（纯黑，无渐变无辉光）
        float arm = side * 0.16f, inset = dp(4);
        cornerPaint.setStrokeWidth(dp(4.5f));
        cornerPaint.setShader(null);
        float l = frame.left + inset, t = frame.top + inset;
        float rt = frame.right - inset, b = frame.bottom - inset;
        Path p = new Path();
        p.moveTo(l, t + arm); p.lineTo(l, t + r); p.quadTo(l, t, l + r, t); p.lineTo(l + arm, t);
        p.moveTo(rt - arm, t); p.lineTo(rt - r, t); p.quadTo(rt, t, rt, t + r); p.lineTo(rt, t + arm);
        p.moveTo(rt, b - arm); p.lineTo(rt, b - r); p.quadTo(rt, b, rt - r, b); p.lineTo(rt - arm, b);
        p.moveTo(l + arm, b); p.lineTo(l + r, b); p.quadTo(l, b, l, b - r); p.lineTo(l, b - arm);
        canvas.drawPath(p, cornerPaint);

        // ④ 往复扫描线（横向渐隐 + 辉光）
        float ly = frame.top + dp(14) + fraction * (frame.height() - dp(28));
        float lw = frame.width() * 0.82f;
        float lh = dp(2.2f);
        linePaint.setColor(ACCENT);
        linePaint.setAlpha(140);
        linePaint.setShadowLayer(0, 0, 0, 0);
        RectF lineRect = new RectF(cx - lw / 2, ly - lh / 2, cx + lw / 2, ly + lh / 2);
        canvas.drawRoundRect(lineRect, lh / 2, lh / 2, linePaint);
        // 线端亮点
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(ACCENT);
        canvas.drawCircle(cx + lw / 2, ly, dp(2.5f), dot);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
