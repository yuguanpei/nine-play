package com.nova5bike.dashboard;

public final class DisplayTransitionsTest {
    public static void main(String[] args) {
        curveGrowth();
        System.out.println("Curve animation: passed");
    }

    private static void curveGrowth() {
        ChartAnimation curve = new ChartAnimation();
        curve.moveTo(1000, 1000, 1000);
        near(curve.valueAt(1000), 1000);
        curve.moveTo(2000, 2000, 1000);
        near(curve.valueAt(2000), 1000);
        near(curve.valueAt(2250), 1250);
        near(curve.valueAt(2500), 1500);
        curve.moveTo(3000, 2500, 1000);
        near(curve.valueAt(2500), 1500);
        near(curve.valueAt(3000), 2250);
        // Duplicate measurements must not restart an unfinished reveal.
        curve.moveTo(3000, 3000, 1000);
        near(curve.valueAt(3500), 3000);
        check(!curve.active(3500), "animation must finish");
        curve.moveTo(Double.NaN, 3600, 1000);
        near(curve.valueAt(3600), 3000);
        curve.snap(8000);
        near(curve.valueAt(3700), 8000);
        check(!curve.active(3700), "a data gap must snap without drawing across the gap");
        check(ChartAnimation.sampleDuration(250) == 250, "fast samples");
        check(ChartAnimation.sampleDuration(1000) == 1000, "1Hz samples");
        check(ChartAnimation.sampleDuration(10000) == 1200, "bounded transition");
    }

    private static void near(double actual, double expected) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) < .001,
                "expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
