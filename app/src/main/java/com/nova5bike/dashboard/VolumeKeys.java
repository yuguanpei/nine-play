package com.nova5bike.dashboard;

/** Recognizes single-press and double-press gestures on a volume key. */
final class VolumeKeys {
    interface Listener {
        /** A single press, resolved only after the double-press window elapses. */
        void single(boolean up);
        /** A quick double press. */
        void skip(boolean up);
    }

    private static final int IDLE = 0, FIRST_DOWN = 1, FIRST_UP = 2, SECOND_DOWN = 3;
    private static final long DOUBLE_WINDOW_MS = 350;

    private final Listener listener;
    private int state = IDLE;
    private boolean up;
    private long firstUpAt, resolveAt = -1;

    VolumeKeys(Listener listener) {
        this.listener = listener;
    }

    /** A volume key went down. {@code repeat} is true for Android's held-key repeat events. */
    boolean down(boolean up, boolean repeat, long now) {
        switch (state) {
            case IDLE:
                this.up = up;
                state = FIRST_DOWN;
                break;
            case FIRST_DOWN:
                break; // a held first press: single still fires on release
            case FIRST_UP:
                if (!repeat && now - firstUpAt <= DOUBLE_WINDOW_MS) {
                    this.up = up;
                    resolveAt = -1;
                    state = SECOND_DOWN;
                } else {
                    this.up = up;
                    state = FIRST_DOWN; // too late for a double: start a new first press
                }
                break;
            case SECOND_DOWN:
                break; // a held second press: skip still fires on release
        }
        return true;
    }

    boolean up(boolean up, long now) {
        switch (state) {
            case FIRST_DOWN:
                firstUpAt = now;
                resolveAt = now + DOUBLE_WINDOW_MS;
                state = FIRST_UP;
                break;
            case FIRST_UP:
                state = IDLE;
                resolveAt = -1;
                listener.single(this.up);
                break;
            case SECOND_DOWN:
                state = IDLE;
                resolveAt = -1;
                listener.skip(up);
                break;
            case IDLE:
                break;
        }
        return true;
    }

    /** Resolve a deferred single press once the double-press window has elapsed. */
    void resolve(long now) {
        if (state == FIRST_UP && resolveAt > 0 && now >= resolveAt) {
            state = IDLE;
            resolveAt = -1;
            listener.single(up);
        }
    }

    long resolveAt() { return resolveAt; }

    int state() { return state; }
}
