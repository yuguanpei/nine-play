package com.nova5bike.dashboard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** Slides the dashboard while keeping the map renderer at a fixed size. */
final class DashboardStage extends FrameLayout {
    private static final float COMPACT_PANEL_FRACTION = .32f;
    private static final long TAP_DURATION_MS = 560;
    private enum Gesture { NONE, TRACKING, DRAGGING, IGNORED }

    interface ModeListener {
        void layoutInstruments(boolean compact);
        void settleMode(boolean compact);
        DashboardTransition.Scene captureInstruments(View pane);
    }

    static final class MapPanel extends FrameLayout {
        private final Rect hitBounds = new Rect();

        MapPanel(Context context) { super(context); }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getClipBounds(hitBounds)
                    && !hitBounds.contains((int) event.getX(), (int) event.getY())) {
                return false;
            }
            return super.dispatchTouchEvent(event);
        }
    }

    private final int gutter, edgeReach, touchSlop;
    private final float mapCornerRadius;
    private final DashboardTransition transition;
    private final Rect mapClip = new Rect();
    private View mapPanel, mapContent, instruments, compactInstruments, mapChrome, compactPanelBackground, mapEdgeShade;
    private ModeListener modeListener;
    private boolean compact, mountedCompact, moving;
    private Gesture gesture = Gesture.NONE;
    private float progress, touchX, touchY;
    private ValueAnimator animation;

    DashboardStage(Context context, int gutter, int edgeReach, float mapCornerRadius) {
        super(context);
        this.gutter = gutter;
        this.edgeReach = edgeReach;
        this.mapCornerRadius = mapCornerRadius;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        transition = new DashboardTransition(context);
    }

    void attachPanels(View mapPanel, View mapContent, View instruments, View compactInstruments, View mapChrome,
            View compactPanelBackground, View mapEdgeShade, ModeListener listener) {
        this.mapPanel = mapPanel;
        this.mapContent = mapContent;
        this.instruments = instruments;
        this.compactInstruments = compactInstruments;
        this.mapChrome = mapChrome;
        this.compactPanelBackground = compactPanelBackground;
        this.mapEdgeShade = mapEdgeShade;
        modeListener = listener;
        mapPanel.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mapCornerRadius * (1 - progress));
            }
        });
        mapPanel.setClipToOutline(true);
        addView(compactPanelBackground, new LayoutParams(0, -1, Gravity.LEFT));
        addView(mapPanel, new LayoutParams(0, -1, Gravity.RIGHT));
        addView(instruments, new LayoutParams(0, -1, Gravity.LEFT));
        addView(compactInstruments, new LayoutParams(-1, -1));
        addView(transition, new LayoutParams(-1, -1));
        compactInstruments.setVisibility(INVISIBLE);
        setProgress(0);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (mapPanel != null) {
            int width = MeasureSpec.getSize(widthSpec);
            int fullWidth = expandedInstrumentsWidth(width);
            ((LayoutParams) instruments.getLayoutParams()).width = fullWidth;
            ((LayoutParams) mapPanel.getLayoutParams()).width = expandedMapWidth(width);
            int compactWidth = compactPanelWidth(width);
            ((LayoutParams) compactPanelBackground.getLayoutParams()).width = compactWidth;
            ((LayoutParams) mapEdgeShade.getLayoutParams()).width = Math.round(compactWidth / 3f);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateMapClip();
    }

    private int expandedInstrumentsWidth(int width) {
        return Math.max(0, Math.round((width - gutter) / 2.7f));
    }

    private int expandedMapWidth(int width) { return Math.max(0, width - expandedInstrumentsWidth(width) - gutter); }
    static int compactPanelWidth(int width) { return Math.round(width * COMPACT_PANEL_FRACTION); }

    boolean compact() { return compact; }

    float mapSideInset() { return compact ? mapTravel() * .5f : 0; }

    private int mapTravel() { return Math.max(0, expandedMapWidth(getWidth()) - compactPanelWidth(getWidth())); }

    void showCompact(boolean enabled, boolean animate) {
        if (mapPanel == null || animation != null) return;
        float end = enabled ? 1 : 0;
        float remaining = Math.abs(progress - end);
        if (!animate || getWidth() == 0 || remaining * mapTravel() < .5f) {
            finishMode(enabled);
            return;
        }
        boolean released = moving;
        if (!released) beginTransition(enabled);
        animation = ValueAnimator.ofFloat(progress, end);
        animation.setDuration(released ? 130 + Math.round(230 * remaining) : TAP_DURATION_MS);
        animation.setInterpolator(released ? new DecelerateInterpolator(1.35f)
                : new AccelerateDecelerateInterpolator());
        animation.addUpdateListener(value -> setProgress((float) value.getAnimatedValue()));
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                animation = null;
                finishMode(enabled);
            }
        });
        animation.start();
    }

    private void beginTransition(boolean target) {
        int width = getWidth();
        int height = getHeight();
        View origin = compact ? compactInstruments : instruments;
        DashboardTransition.Scene before = modeListener.captureInstruments(origin);
        moving = true;
        mount(target);
        View destination = target ? compactInstruments : instruments;
        int paneWidth = target ? width : expandedInstrumentsWidth(width);
        destination.measure(MeasureSpec.makeMeasureSpec(paneWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        destination.layout(0, 0, paneWidth, height);
        DashboardTransition.Scene after = modeListener.captureInstruments(destination);
        instruments.setVisibility(INVISIBLE);
        compactInstruments.setVisibility(INVISIBLE);
        transition.show(compact ? after : before, compact ? before : after);
        mapChrome.setVisibility(VISIBLE);
        setProgress(progress);
        requestLayout();
    }

    private void mount(boolean enabled) {
        if (mountedCompact == enabled) return;
        mountedCompact = enabled;
        modeListener.layoutInstruments(enabled);
    }

    private void finishMode(boolean enabled) {
        compact = enabled;
        moving = false;
        mount(compact);
        instruments.setVisibility(compact ? INVISIBLE : VISIBLE);
        compactInstruments.setVisibility(compact ? VISIBLE : INVISIBLE);
        transition.clear();
        setProgress(compact ? 1 : 0);
        modeListener.settleMode(compact);
        requestLayout();
    }

    private void setProgress(float value) {
        progress = value;
        transition.setProgress(value);
        mapChrome.setAlpha(Math.max(0, 1 - 1.65f * value));
        compactPanelBackground.setAlpha(moving ? value : 0);
        mapEdgeShade.setAlpha(value);
        updateMapClip();
        mapPanel.invalidateOutline();
    }

    private void updateMapClip() {
        if (mapPanel == null) return;
        int clip = Math.round(mapTravel() * progress);
        if (clip == 0) {
            mapPanel.setClipBounds(null);
        } else {
            mapClip.set(clip, 0, mapPanel.getWidth(), mapPanel.getHeight());
            mapPanel.setClipBounds(mapClip);
        }
        // Shift the rendered map by half the clip so the rider stays centered in the visible rectangle.
        mapContent.setTranslationX(clip * .5f);
        mapEdgeShade.setTranslationX(clip);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (animation != null) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchX = event.getX();
            touchY = event.getY();
            int middle = expandedInstrumentsWidth(getWidth()) + gutter / 2;
            boolean canSlide = compact ? touchX >= getWidth() - compactPanelWidth(getWidth())
                    : Math.abs(touchX - middle) <= edgeReach;
            gesture = canSlide ? Gesture.TRACKING : Gesture.NONE;
            return super.dispatchTouchEvent(event) || canSlide;
        }
        if (gesture == Gesture.NONE) return super.dispatchTouchEvent(event);
        if (gesture == Gesture.IGNORED) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gesture = Gesture.NONE;
            return true;
        }

        float dx = event.getX() - touchX;
        float dy = event.getY() - touchY;
        float travel = compact ? -dx : dx;
        boolean horizontal = travel > Math.abs(dy) * 1.5f;
        if (action == MotionEvent.ACTION_MOVE && gesture == Gesture.TRACKING && Math.hypot(dx, dy) > touchSlop) {
            if (horizontal) {
                cancelChildTouch(event);
                gesture = Gesture.DRAGGING;
                beginTransition(!compact);
            } else if (compact) {
                // A drag inside the map button should not turn into a tap on release.
                cancelChildTouch(event);
                gesture = Gesture.IGNORED;
                return true;
            } else gesture = Gesture.NONE;
        }
        if (gesture == Gesture.DRAGGING && action == MotionEvent.ACTION_MOVE) {
            float fraction = Math.max(0, Math.min(1, travel / Math.max(1, mapTravel())));
            setProgress(compact ? 1 - fraction : fraction);
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (gesture == Gesture.DRAGGING) {
                boolean commit = action == MotionEvent.ACTION_UP && horizontal && travel >= getWidth() * .135f;
                gesture = action == MotionEvent.ACTION_POINTER_DOWN ? Gesture.IGNORED : Gesture.NONE;
                showCompact(commit ? !compact : compact, true);
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN && compact && gesture == Gesture.TRACKING) {
                cancelChildTouch(event);
                gesture = Gesture.IGNORED;
                return true;
            }
            gesture = Gesture.NONE;
        }
        return super.dispatchTouchEvent(event) || gesture == Gesture.TRACKING;
    }

    private void cancelChildTouch(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    @Override protected void onDetachedFromWindow() {
        if (animation != null) {
            animation.removeAllListeners();
            animation.cancel();
            animation = null;
        }
        transition.clear();
        super.onDetachedFromWindow();
    }
}
