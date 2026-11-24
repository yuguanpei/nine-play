package com.nova5bike.dashboard;

public final class RouteSamplesTest {
    public static void main(String[] args) {
        RouteSamples s = new RouteSamples();
        s.append(1000, 30, false, 6000);
        s.append(1004, 32, false, 6000);
        check(s.count == 2 && s.speeds[1] == 32, "same-bin positive samples retain the peak");
        s.append(1005, 0, false, 6000);
        check(s.count == 3 && s.speeds[1] == 32 && s.speeds[2] == 0,
                "a new stop must not replace the preceding measured peak");
        for (int i = 0; i < 100; i++) s.append(1005, 0, false, 6000);
        check(s.count == 3, "a stationary stop must not grow the buffer");
        s.append(1006, 28, false, 6000);
        check(s.count == 4 && s.speeds[2] == 0 && s.speeds[3] == 28, "starting preserves both zero and positive readings");
        s.append(1006, 0, false, 6000);
        check(s.speeds[3] == 28 && s.speeds[4] == 0, "a zero-distance stop preserves the previous speed");
        s.append(1007, 20, true, 6000);
        check(s.starts[s.count - 1], "missing route data remains a real gap");
        for (int i = 0; i < 10000; i++) s.append(1020 + i * .1, i % 2 == 0 ? 0 : 35, i % 40 == 0, 6000);
        check(s.count <= RouteSamples.CAPACITY, "route history must stay bounded across repeated stops");
        float peak = 0;
        for (int i = 0; i < s.count; i++) peak = Math.max(peak, s.speeds[i]);
        check(peak == 35, "compaction must retain the measured peak");
        System.out.println("Route historical peaks, same-bin stops, gaps and bounded compaction: passed");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
