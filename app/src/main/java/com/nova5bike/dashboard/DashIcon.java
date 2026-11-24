package com.nova5bike.dashboard;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Small vector icons rendered locally, without relying on system glyph shapes. */
final class DashIcon extends Drawable {
    enum Kind { NORTH, LEFT, RIGHT, LEFT_AHEAD, RIGHT_AHEAD, RETURN, TARGET, SEARCH, MUSIC, PREVIOUS,
        NEXT, PLAY, PAUSE, PIN, CHECK, CLOSE, ROUTE, MESSAGE, WECHAT, TELEGRAM, SETTINGS, BLUETOOTH, WIFI, CELLULAR,
        BATTERY, SUN, MOON, HOME, CLOUD, RAIN, THUNDER, SNOW, FOG }

    private static final int WECHAT_GREEN = 0xff07c160;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private Kind kind;
    private final int size;
    private int signalLevel;
    private int batteryLevel = -1;
    private boolean batteryCharging;

    DashIcon(Kind kind, int color, int size) {
        this.size = size;
        this.kind = kind;
        paint.setColor(color);
        paint.setStrokeWidth(2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setBounds(0, 0, size, size);
    }

    void setKind(Kind value) {
        if (kind == value) return;
        kind = value;
        invalidateSelf();
    }

    void setColor(int color) {
        if (paint.getColor() != color) {
            paint.setColor(color);
            invalidateSelf();
        }
    }

    void setSignalLevel(int level) {
        if (signalLevel != level) {
            signalLevel = level;
            invalidateSelf();
        }
    }

    void setBattery(int level, boolean charging) {
        if (batteryLevel != level || batteryCharging != charging) {
            batteryLevel = level;
            batteryCharging = charging;
            invalidateSelf();
        }
    }

    @Override public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        paint.setStyle(Paint.Style.STROKE);
        switch (kind) {
            case CLOUD:
            case RAIN:
            case THUNDER:
            case SNOW:
            case FOG:
                path.reset();
                path.moveTo(6, 16);
                path.cubicTo(0, 16, 1, 8, 7, 9);
                path.cubicTo(8, 2, 18, 3, 18, 10);
                path.cubicTo(24, 9, 24, 16, 18, 16);
                path.close();
                canvas.drawPath(path, paint);
                if (kind == Kind.RAIN) {
                    stroke(canvas, 6, 19, 5, 22);
                    stroke(canvas, 12, 19, 11, 22);
                    stroke(canvas, 18, 19, 17, 22);
                } else if (kind == Kind.THUNDER) stroke(canvas, 14, 17, 10, 20, 13, 20, 11, 23);
                else if (kind == Kind.SNOW) {
                    stroke(canvas, 7, 19, 7, 23);
                    stroke(canvas, 5, 21, 9, 21);
                    stroke(canvas, 17, 19, 17, 23);
                    stroke(canvas, 15, 21, 19, 21);
                } else if (kind == Kind.FOG) {
                    stroke(canvas, 4, 19, 20, 19);
                    stroke(canvas, 7, 22, 17, 22);
                }
                break;
            case HOME:
                stroke(canvas, 3, 11, 12, 3, 21, 11);
                stroke(canvas, 6, 9, 6, 21, 10, 21, 10, 15, 14, 15, 14, 21, 18, 21, 18, 9);
                break;
            case NORTH:
                stroke(canvas, 12, 20, 12, 4, 6, 10);
                stroke(canvas, 12, 4, 18, 10);
                break;
            case RIGHT:
            case LEFT:
                if (kind == Kind.RIGHT) mirror(canvas);
                stroke(canvas, 19, 20, 19, 9, 4, 9, 9, 4);
                stroke(canvas, 4, 9, 9, 14);
                break;
            case RIGHT_AHEAD:
            case LEFT_AHEAD:
                if (kind == Kind.RIGHT_AHEAD) mirror(canvas);
                stroke(canvas, 18, 20, 18, 14, 7, 4, 7, 11);
                stroke(canvas, 7, 4, 14, 4);
                break;
            case RETURN:
                path.reset();
                path.moveTo(19, 20);
                path.lineTo(19, 9);
                path.cubicTo(19, 2, 7, 2, 7, 9);
                path.lineTo(7, 16);
                canvas.drawPath(path, paint);
                stroke(canvas, 3, 12, 7, 16, 11, 12);
                break;
            case TARGET:
                canvas.drawCircle(12, 12, 7, paint);
                canvas.drawCircle(12, 12, 2, paint);
                stroke(canvas, 12, 2, 12, 4);
                stroke(canvas, 12, 20, 12, 22);
                stroke(canvas, 2, 12, 4, 12);
                stroke(canvas, 20, 12, 22, 12);
                break;
            case BLUETOOTH:
                stroke(canvas, 6, 6, 17, 17, 12, 22, 12, 2, 17, 7, 6, 18);
                break;
            case WIFI:
                drawWifi(canvas);
                break;
            case CELLULAR:
                drawCellular(canvas);
                break;
            case BATTERY:
                drawBattery(canvas);
                break;
            case SEARCH:
                canvas.drawCircle(10, 10, 6, paint);
                stroke(canvas, 14.5f, 14.5f, 20, 20);
                break;
            case SETTINGS:
                canvas.drawCircle(12, 12, 3, paint);
                trace(new float[]{10, 3, 14, 3, 15, 6, 18, 6, 21, 10, 19, 12, 21, 14,
                        18, 18, 15, 18, 14, 21, 10, 21, 9, 18, 6, 18, 3, 14, 5, 12, 3, 10, 6, 6, 9, 6});
                path.close();
                canvas.drawPath(path, paint);
                break;
            case SUN:
                canvas.drawCircle(12, 12, 4, paint);
                for (int ray = 0; ray < 8; ray++) {
                    canvas.drawLine(12, 2, 12, 4.5f, paint);
                    canvas.rotate(45, 12, 12);
                }
                break;
            case MOON:
                path.reset();
                path.moveTo(11, 3);
                path.cubicTo(5, 3.8f, 2.4f, 8, 3.5f, 13.6f);
                path.cubicTo(4.9f, 21, 15.9f, 23.2f, 20, 15.5f);
                path.cubicTo(13.7f, 17, 8.1f, 10.2f, 11, 3);
                path.close();
                canvas.drawPath(path, paint);
                break;
            case MUSIC:
                stroke(canvas, 9, 18, 9, 5, 20, 3, 20, 15);
                canvas.drawOval(3, 15.5f, 9, 20.5f, paint);
                canvas.drawOval(14, 12.5f, 20, 17.5f, paint);
                break;
            case MESSAGE:
                paint.setStyle(Paint.Style.FILL);
                canvas.drawOval(3, 4, 21, 18, paint);
                fill(canvas, 6, 14, 5, 21, 12, 17);
                break;
            case WECHAT:
                drawWeChat(canvas);
                break;
            case TELEGRAM:
                drawTelegram(canvas);
                break;
            case PREVIOUS:
            case NEXT:
                if (kind == Kind.PREVIOUS) mirror(canvas);
                stroke(canvas, 19, 5, 19, 19);
                fill(canvas, 5, 5, 16, 12, 5, 19);
                break;
            case PLAY:
                fill(canvas, 7, 4, 20, 12, 7, 20);
                break;
            case PAUSE:
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(6, 5, 10, 19, 1, 1, paint);
                canvas.drawRoundRect(14, 5, 18, 19, 1, 1, paint);
                break;
            case PIN:
                path.reset();
                path.moveTo(12, 21);
                path.cubicTo(9, 17, 5, 13, 5, 9);
                path.cubicTo(5, 0, 19, 0, 19, 9);
                path.cubicTo(19, 13, 15, 17, 12, 21);
                canvas.drawPath(path, paint);
                canvas.drawCircle(12, 9, 2, paint);
                break;
            case CHECK:
                stroke(canvas, 4, 12, 9, 17, 20, 6);
                break;
            case CLOSE:
                paint.setStrokeWidth(1.5f);
                stroke(canvas, 6, 6, 18, 18);
                stroke(canvas, 18, 6, 6, 18);
                paint.setStrokeWidth(2f);
                break;
            case ROUTE:
                canvas.drawCircle(5, 19, 2, paint);
                canvas.drawCircle(19, 5, 2, paint);
                stroke(canvas, 7, 19, 15, 19, 15, 12, 9, 12, 9, 5, 17, 5);
                break;
        }
        canvas.restore();
    }

    private void drawWeChat(Canvas canvas) {
        int previousColor = paint.getColor();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(WECHAT_GREEN);
        canvas.drawRoundRect(0, 0, 24, 24, 5.5f, 5.5f, paint);
        paint.setColor(0xffffffff);
        canvas.drawOval(3.4f, 5.4f, 16.1f, 15.6f, paint);
        fill(canvas, 5.6f, 13.6f, 4.9f, 16.6f, 8.5f, 14.8f);
        paint.setColor(WECHAT_GREEN);
        canvas.drawCircle(7.4f, 9.5f, .83f, paint);
        canvas.drawCircle(11.7f, 9.5f, .83f, paint);
        canvas.drawOval(9.9f, 9.6f, 21.8f, 19.4f, paint);
        paint.setColor(0xffffffff);
        canvas.drawOval(10.4f, 10.1f, 21.3f, 18.9f, paint);
        fill(canvas, 16.9f, 18.3f, 19.1f, 20f, 18.6f, 17.6f);
        paint.setColor(WECHAT_GREEN);
        canvas.drawCircle(14.1f, 13.6f, .72f, paint);
        canvas.drawCircle(17.7f, 13.6f, .72f, paint);
        paint.setColor(previousColor);
    }

    private void drawTelegram(Canvas canvas) {
        int previousColor = paint.getColor();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff2aabee);
        canvas.drawRoundRect(0, 0, 24, 24, 5.5f, 5.5f, paint);
        paint.setColor(0xffffffff);
        fill(canvas, 3.8f, 11.4f, 20.1f, 5.1f, 17.4f, 19.3f,
                12.8f, 15.8f, 10.1f, 18f, 10.4f, 14.5f, 17.4f, 8.1f, 8.6f, 13.5f);
        paint.setColor(previousColor);
    }

    private void drawWifi(Canvas canvas) {
        int alpha = paint.getAlpha();
        for (int i = 1; i <= 3; i++) {
            float radius = 4 * i;
            paint.setAlpha(signalLevel >= i + 1 ? alpha : alpha / 3);
            canvas.drawArc(12 - radius, 19 - radius, 12 + radius, 19 + radius, 225, 90, false, paint);
        }
        paint.setAlpha(signalLevel > 0 ? alpha : alpha / 3);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(12, 20, 1.5f, paint);
        paint.setAlpha(alpha);
    }

    private void drawCellular(Canvas canvas) {
        int alpha = paint.getAlpha();
        for (int i = 0; i < 4; i++) {
            float x = 5 + 4.5f * i;
            paint.setAlpha(signalLevel > i ? alpha : alpha / 3);
            canvas.drawLine(x, 19, x, 15 - i * 3, paint);
        }
        paint.setAlpha(alpha);
    }

    private void drawBattery(Canvas canvas) {
        int color = paint.getColor();
        canvas.drawRoundRect(2, 6, 20, 18, 2.4f, 2.4f, paint);
        canvas.drawLine(23, 10, 23, 14, paint);
        if (batteryLevel > 0) {
            paint.setStyle(Paint.Style.FILL);
            if (batteryCharging) paint.setAlpha(115);
            canvas.drawRoundRect(5, 9, 5 + 12 * batteryLevel / 100f, 15, 1, 1, paint);
            paint.setColor(color);
        }
        if (batteryCharging) {
            trace(new float[]{12, 6.5f, 7.5f, 12.5f, 10.8f, 12.5f, 9.8f, 17.5f, 15, 10.8f, 12.1f, 10.8f});
            path.close();
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xff111820);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawPath(path, paint);
        }
    }

    private static void mirror(Canvas canvas) {
        canvas.translate(24, 0);
        canvas.scale(-1, 1);
    }

    private void stroke(Canvas canvas, float... points) {
        trace(points);
        canvas.drawPath(path, paint);
    }

    private void fill(Canvas canvas, float... points) {
        trace(points);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
    }

    private void trace(float[] points) {
        path.reset();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
    }

    @Override public void setAlpha(int value) { paint.setAlpha(value); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
