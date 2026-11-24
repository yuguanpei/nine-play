package com.nova5bike.dashboard;

/** Main-thread lifecycle of a route request, including superseded network callbacks. */
final class NavigationPlan {
    enum Phase { IDLE, WAITING_FOR_FIX, PLANNING, ACTIVE, FAILED }
    private Phase phase = Phase.IDLE;
    private int generation;

    boolean waiting() { return phase == Phase.WAITING_FOR_FIX; }
    boolean planning() { return phase == Phase.PLANNING; }
    boolean failed() { return phase == Phase.FAILED; }
    boolean resumable() { return phase != Phase.IDLE && phase != Phase.FAILED; }

    void keepExistingRoute() { phase = Phase.ACTIVE; generation++; }
    void clear() { phase = Phase.IDLE; generation++; }
    void queue() { phase = Phase.WAITING_FOR_FIX; generation++; }
    int start() { phase = Phase.PLANNING; return ++generation; }

    boolean finish(int request, boolean hasUsableRoute) {
        if (!planning() || request != generation) return false;
        phase = hasUsableRoute ? Phase.ACTIVE : Phase.FAILED;
        return true;
    }
}
