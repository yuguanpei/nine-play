package com.nova5bike.dashboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/** Draws individual instrument pieces between their measured expanded and compact bounds. */
final class DashboardTransition extends View {

    static final class Scene {
        private enum Capture { VIEW, BACKGROUND, ICON, TEXT }

        private final int[] origin = new int[2];
        private final int[] location = new int[2];
        private final View pane;
        private final CompactPanel compactPanel;
        private final float fadeStart, fadeEnd;
        private final Map<String, Piece> pieces = new LinkedHashMap<>();
        private TextLine title, artist;
        private RectF speedBounds, mediaBounds;

        Scene(View stage, View pane, CompactPanel compactPanel) {
            stage.getLocationInWindow(origin);
            this.pane = pane;
            this.compactPanel = compactPanel;
            if (compactPanel == null) {
                fadeStart = fadeEnd = 0;
            } else {
                compactPanel.getLocationInWindow(location);
                float left = location[0] - origin[0];
                fadeStart = left + CompactPanel.fadeStart(compactPanel.getWidth());
                fadeEnd = left + compactPanel.getWidth();
            }
        }

        org.json.JSONObject diagnostics() {
            org.json.JSONObject data = new org.json.JSONObject();
            try {
                for (Map.Entry<String, Piece> entry : pieces.entrySet()) {
                    Piece piece = entry.getValue();
                    org.json.JSONObject value = new org.json.JSONObject();
                    value.put("bounds", piece.bounds.toShortString()).put("alpha", piece.alpha)
                            .put("panelContent", piece.panelContent);
                    data.put(entry.getKey(), value);
                }
                data.put("fadeStart", fadeStart).put("fadeEnd", fadeEnd);
                if (title != null) data.put("titleGeometry", lineGeometry(title));
                if (artist != null) data.put("artistGeometry", lineGeometry(artist));
            } catch (org.json.JSONException ignored) {}
            return data;
        }

        private org.json.JSONObject lineGeometry(TextLine line) throws org.json.JSONException {
            org.json.JSONObject data = new org.json.JSONObject();
            return data.put("frame", line.frame.toShortString()).put("baseline", line.baseline)
                    .put("x", line.x).put("alpha", line.alpha).put("fontPx", line.style.getTextSize())
                    .put("textLength", line.text.length());
        }

        void add(String key, View view) { capture(key, view, Capture.VIEW); }
        void icon(String key, ImageView view) { capture(key, view, Capture.ICON); }
        void text(String key, View view) { capture(key, view, Capture.TEXT); }

        void cover(ImageView view) {
            RectF bounds = backgroundBounds(view);
            if (bounds == null || !visibleInPane(view)) return;
            Drawable art = view.getDrawable();
            if (art == null) return;
            Drawable copy;
            if (art instanceof MusicCoverDrawable) copy = ((MusicCoverDrawable) art).copyForTransition();
            else if (art.getConstantState() != null) copy = art.getConstantState().newDrawable(view.getResources()).mutate();
            else {
                add("cover", view);
                return;
            }
            pieces.put("cover", new Piece(null, null, copy, bounds, view.getAlpha(), belongsToCompactPanel(view)));
        }

        void speedBackground(View view) {
            speedBounds = backgroundBounds(view);
            capture("speed background", view, Capture.BACKGROUND);
        }

        void mediaBackground(View view) {
            mediaBounds = backgroundBounds(view);
            capture("media background", view, Capture.BACKGROUND);
        }

        private RectF backgroundBounds(View view) {
            if (view.getWidth() == 0 || view.getHeight() == 0) return null;
            view.getLocationInWindow(location);
            int x = location[0] - origin[0], y = location[1] - origin[1];
            return new RectF(x, y, x + view.getWidth(), y + view.getHeight());
        }
        void media(TextView title, TextView artist) {
            this.title = mediaLine(title);
            this.artist = mediaLine(artist);
        }

        private TextLine mediaLine(TextView view) {
            if (view.getWidth() == 0 || view.getHeight() == 0 || view.getText().length() == 0) return null;
            view.getLocationInWindow(location);
            int x = location[0] - origin[0], y = location[1] - origin[1];
            RectF frame = new RectF(x + view.getCompoundPaddingLeft(), y,
                    x + view.getWidth() - view.getCompoundPaddingRight(), y + view.getHeight());
            Paint style = new Paint(view.getPaint());
            style.setColor(view.getCurrentTextColor());
            int gravity = Gravity.getAbsoluteGravity(view.getGravity(), view.getLayoutDirection())
                    & Gravity.HORIZONTAL_GRAVITY_MASK;
            String text = view.getText().toString();
            float width = Math.min(frame.width(), style.measureText(text));
            float start = gravity == Gravity.RIGHT ? frame.right - width : frame.left;
            return new TextLine(text, frame, style, y + view.getBaseline(), start,
                    visibleInPane(view) ? view.getAlpha() : 0, view instanceof ScrollingTitle ? (ScrollingTitle) view : null);
        }

        private void capture(String key, View view, Capture type) {
            if (view == null || view.getWidth() == 0 || view.getHeight() == 0 || !visibleInPane(view)) return;
            Drawable background = view.getBackground();
            if (type == Capture.BACKGROUND && background == null) return;
            int width = view.getWidth();
            int height = view.getHeight();
            int insetX = 0;
            int insetY = 0;
            if (type == Capture.ICON) {
                Drawable glyph = ((ImageView) view).getDrawable();
                if (glyph != null) {
                    int left = Math.round((width - glyph.getIntrinsicWidth()
                            + view.getPaddingLeft() - view.getPaddingRight()) / 2f);
                    int top = Math.round((height - glyph.getIntrinsicHeight()
                            + view.getPaddingTop() - view.getPaddingBottom()) / 2f);
                    insetX = Math.max(0, left - 1);
                    insetY = Math.max(0, top - 1);
                    width = Math.min(width, left + glyph.getIntrinsicWidth() + 1) - insetX;
                    height = Math.min(height, top + glyph.getIntrinsicHeight() + 1) - insetY;
                }
            }
            view.getLocationInWindow(location);
            Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(image);
                int saved = canvas.save();
                canvas.translate(-insetX, -insetY);
                if (type == Capture.BACKGROUND) {
                    Rect previousBounds = background.copyBounds();
                    background.setBounds(0, 0, view.getWidth(), view.getHeight());
                    try {
                        background.draw(canvas);
                    } finally {
                        background.setBounds(previousBounds);
                    }
                } else {
                    canvas.translate(-view.getScrollX(), -view.getScrollY());
                    view.draw(canvas);
                }
                canvas.restoreToCount(saved);
            } catch (IllegalArgumentException failure) {
                // A hardware-only album bitmap cannot be drawn onto a software canvas.
                image.recycle();
                return;
            }
            Rect visible = type == Capture.TEXT ? inkBounds(image, 0, width) : new Rect(0, 0, width, height);
            if (visible.isEmpty()) {
                image.recycle();
                return;
            }
            float x = location[0] - origin[0] + insetX;
            float y = location[1] - origin[1] + insetY;
            RectF bounds = new RectF(x + visible.left, y + visible.top, x + visible.right, y + visible.bottom);
            pieces.put(key, new Piece(image, visible, null, bounds, view.getAlpha(), belongsToCompactPanel(view)));
        }

        private boolean belongsToCompactPanel(View view) {
            if (compactPanel == null) return false;
            for (ViewParent parent = view.getParent(); parent instanceof View && parent != pane;
                    parent = parent.getParent()) {
                if (parent == compactPanel) return true;
            }
            return false;
        }

        private static Rect inkBounds(Bitmap image, int left, int right) {
            int width = right - left;
            int height = image.getHeight();
            Rect bounds = new Rect(right, height, left, 0);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                image.getPixels(row, 0, width, left, y, width, 1);
                for (int x = 0; x < width; x++) {
                    if ((row[x] >>> 24) == 0) continue;
                    bounds.left = Math.min(bounds.left, left + x);
                    bounds.right = Math.max(bounds.right, left + x + 1);
                    bounds.top = Math.min(bounds.top, y);
                    bounds.bottom = y + 1;
                }
            }
            return bounds;
        }

        private boolean visibleInPane(View view) {
            for (View current = view; current != pane; ) {
                if (current.getVisibility() != VISIBLE) return false;
                ViewParent parent = current.getParent();
                if (!(parent instanceof View)) return false;
                current = (View) parent;
            }
            return true;
        }
    }

    private static final class TextLine {
        final String text;
        final RectF frame;
        final Paint style;
        final float baseline, x, alpha;

        final ScrollingTitle scrollingTitle;

        TextLine(String text, RectF frame, Paint style, float baseline, float x, float alpha, ScrollingTitle scrollingTitle) {
            this.scrollingTitle = scrollingTitle;
            this.text = text;
            this.frame = frame;
            this.style = style;
            this.baseline = baseline;
            this.x = x;
            this.alpha = alpha;
        }
    }

    private static final class PositionedLine {
        final RectF frame = new RectF();
        final Paint paint = new Paint();
        String text;
        float x, baseline, scrollWeight, alpha;
        ScrollingTitle scrollingTitle;
        boolean visible;
    }

    private static final class Piece {
        final Bitmap image;
        final Rect source;
        final Drawable artwork;
        final RectF bounds;
        final float alpha;
        final boolean panelContent;

        Piece(Bitmap image, Rect source, Drawable artwork, RectF bounds, float alpha, boolean panelContent) {
            this.image = image;
            this.artwork = artwork;
            this.source = source;
            this.bounds = bounds;
            this.alpha = alpha;
            this.panelContent = panelContent;
        }
    }

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint panelMask = new Paint();
    private final RectF between = new RectF();
    private final RectF mediaClip = new RectF();
    private final PositionedLine title = new PositionedLine();
    private final PositionedLine artist = new PositionedLine();
    private final float density;
    private Scene expanded, compact;
    private float progress;

    DashboardTransition(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        panelMask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(INVISIBLE);
    }

    void show(Scene expanded, Scene compact) {
        RideLog.event("layout.transition_start", "w", getWidth(), "h", getHeight(),
                "expanded", expanded.diagnostics(), "compact", compact.diagnostics());
        this.expanded = expanded;
        this.compact = compact;
        panelMask.setShader(compact.fadeEnd > compact.fadeStart ? new LinearGradient(
                compact.fadeStart, 0, compact.fadeEnd, 0, 0, 0xff000000, Shader.TileMode.CLAMP) : null);
        setVisibility(VISIBLE);
    }

    void clear() {
        RideLog.event("layout.transition_end", "progress", progress);
        setVisibility(INVISIBLE);
        expanded = compact = null;
    }

    void setProgress(float fraction) {
        if (RideLog.due("layout.transition", 150)) RideLog.event("layout.transition", "progress", fraction);
        progress = fraction;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (expanded == null || compact == null) return;
        panelMask.setAlpha(Math.round(255 * progress));
        positionLine(expanded.title, compact.title, title);
        positionLine(expanded.artist, compact.artist, artist);
        for (Map.Entry<String, Piece> entry : expanded.pieces.entrySet()) {
            if (!mediaOverlay(entry.getKey())) drawPiece(canvas, entry.getKey(), entry.getValue(), compact.pieces.get(entry.getKey()));
        }
        for (Map.Entry<String, Piece> entry : compact.pieces.entrySet()) {
            if (!expanded.pieces.containsKey(entry.getKey()) && !mediaOverlay(entry.getKey())) {
                drawPiece(canvas, entry.getKey(), null, entry.getValue());
            }
        }
        drawMediaLine(canvas, title);
        drawMediaLine(canvas, artist);
        drawPiece(canvas, "shared destination", expanded.pieces.get("shared destination"), compact.pieces.get("shared destination"));
        drawPiece(canvas, "notification", expanded.pieces.get("notification"), compact.pieces.get("notification"));
        drawPiece(canvas, "compact notification", expanded.pieces.get("compact notification"),
                compact.pieces.get("compact notification"));
    }

    private static boolean mediaOverlay(String key) {
        return "shared destination".equals(key) || "notification".equals(key) || "compact notification".equals(key);
    }

    private void drawPiece(Canvas canvas, String key, Piece from, Piece to) {
        if (to == null) {
            if (from == null) return;
            RectF destination = null;
            if ("media background".equals(key)) destination = compact.mediaBounds;
            if ("speed background".equals(key)) destination = compact.speedBounds;
            if (destination != null) {
                interpolate(from.bounds, destination, between);
                draw(canvas, from, between, from.alpha * (1 - progress));
            } else {
                draw(canvas, from, from.bounds, from.alpha * (1 - progress));
            }
            return;
        }
        if (from == null) {
            boolean vehicleDetail = "range label".equals(key) || "battery label".equals(key)
                    || "battery progress".equals(key);
            float opacity = vehicleDetail ? Math.max(0, (progress - .28f) / .72f) : progress;
            draw(canvas, to, to.bounds, to.alpha * opacity);
            return;
        }
        interpolate(from.bounds, to.bounds, between);
        if ("speed curve".equals(key) && expanded.mediaBounds != null && compact.mediaBounds != null) {
            interpolate(expanded.mediaBounds, compact.mediaBounds, mediaClip);
            // At the compact endpoint the chart and the panel already share a fading edge.
            mediaClip.right = mix(expanded.mediaBounds.right, Math.min(compact.mediaBounds.right, to.bounds.left));
            int save = canvas.save();
            canvas.clipOutRect(mediaClip);
            draw(canvas, progress <= 0 ? from : to, between, mix(from.alpha, to.alpha));
            canvas.restoreToCount(save);
            return;
        } else if ("time".equals(key)) {
            // The clock crosses the icon row when it moves from the left to the center.
            between.offset(0, 28 * density * (float) Math.sin(Math.PI * progress));
        } else if ("speed".equals(key) || "unit".equals(key)) {
            // Move the readout below the vehicle readings before it sweeps across them.
            float horizontal = progress * progress * (3 - 2 * progress);
            float remaining = 1 - progress;
            float vertical = 1 - remaining * remaining * remaining;
            between.offset((to.bounds.left - from.bounds.left) * (horizontal - progress),
                    (to.bounds.top - from.bounds.top) * (vertical - progress));

        }
        draw(canvas, progress <= 0 ? from : to, between, mix(from.alpha, to.alpha));
    }

    private void positionLine(TextLine from, TextLine to, PositionedLine line) {
        line.visible = false;
        if (from == null && to == null) return;
        float alpha = mix(from == null ? 0 : from.alpha, to == null ? 0 : to.alpha);
        if (alpha <= 0) return;
        if (from == null) from = to;
        if (to == null) to = from;
        TextLine style = progress <= 0 ? from : to;
        line.text = style.text;
        line.scrollingTitle = style.scrollingTitle;
        line.alpha = alpha;
        if (line.scrollingTitle != null) {
            line.scrollWeight = mix(line.scrollingTitle.overflows(from.frame.width()) ? 1 : 0,
                    line.scrollingTitle.overflows(to.frame.width()) ? 1 : 0);
        }
        line.paint.set(style.style);
        line.paint.setTextSize(mix(from.style.getTextSize(), to.style.getTextSize()));
        line.paint.setAlpha(Math.round(alpha * line.paint.getAlpha()));
        interpolate(from.frame, to.frame, line.frame);
        line.x = mix(from.x, to.x);
        line.baseline = mix(from.baseline, to.baseline);
        line.visible = true;
    }

    private void drawMediaLine(Canvas canvas, PositionedLine line) {
        if (!line.visible || line.frame.width() <= 0) return;
        int layer = savePanelLayer(canvas, line.frame);
        int saved = canvas.save();
        canvas.clipRect(line.frame);
        if (line.scrollingTitle == null) {
            canvas.drawText(line.text, line.x, line.baseline, line.paint);
        } else {
            if (line.alpha < 1) canvas.saveLayerAlpha(line.frame, Math.round(255 * line.alpha));
            if (line.scrollingTitle.drawTitle(canvas, line.x, line.baseline, line.scrollWeight)) {
                postInvalidateOnAnimation();
            }
        }
        canvas.restoreToCount(saved);
        restorePanelLayer(canvas, line.frame, layer);
    }

    private void interpolate(RectF from, RectF to, RectF result) {
        result.set(mix(from.left, to.left), mix(from.top, to.top),
                mix(from.right, to.right), mix(from.bottom, to.bottom));
    }

    private float mix(float from, float to) { return from + (to - from) * progress; }

    private void draw(Canvas canvas, Piece piece, RectF bounds, float opacity) {
        if (opacity <= 0) return;
        int layer = piece.panelContent ? savePanelLayer(canvas, bounds) : -1;
        int alpha = Math.round(255 * opacity);
        if (piece.artwork != null) {
            piece.artwork.setBounds(Math.round(bounds.left), Math.round(bounds.top),
                    Math.round(bounds.right), Math.round(bounds.bottom));
            piece.artwork.setAlpha(alpha);
            piece.artwork.draw(canvas);
        } else {
            paint.setAlpha(alpha);
            canvas.drawBitmap(piece.image, piece.source, bounds, paint);
        }
        restorePanelLayer(canvas, bounds, layer);
    }

    private int savePanelLayer(Canvas canvas, RectF bounds) {
        return progress > 0 && panelMask.getShader() != null && bounds.right > compact.fadeStart
                ? canvas.saveLayer(bounds, null) : -1;
    }

    private void restorePanelLayer(Canvas canvas, RectF bounds, int layer) {
        if (layer < 0) return;
        canvas.drawRect(bounds, panelMask);
        canvas.restoreToCount(layer);
    }
}
