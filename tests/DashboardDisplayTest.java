package com.nova5bike.dashboard;

public final class DashboardDisplayTest {
    public static void main(String[] args) {
        landscapeOnResume();
        unstableOrientation();
        landscapeTablet();
        readingWidths();
        locationAccuracy();
        System.out.println("Dashboard display: passed");
    }

    private static void landscapeOnResume() {
        LandscapeRotation rotation = new LandscapeRotation();
        check(rotation.update(270, false, 0) == -1, "wait for a stable initial sample");
        check(rotation.update(270, false, 250) == 1, "normal landscape without further movement");
        rotation.reset();
        check(rotation.update(90, false, 1000) == -1, "resume starts a fresh assessment");
        check(rotation.update(90, false, 1250) == 3, "resume in the opposite landscape direction");
    }

    private static void unstableOrientation() {
        LandscapeRotation rotation = new LandscapeRotation();
        rotation.update(90, false, 0);
        check(rotation.update(-1, false, 200) == -1, "flat or unknown position must not rotate");
        check(rotation.update(90, false, 300) == -1, "invalid sample interrupts stability");
        check(rotation.update(90, false, 550) == 3, "valid stable reading recovers");
        check(rotation.update(135, false, 800) == -1, "do not flip near the portrait boundary");
        check(rotation.update(0, false, 1100) == -1, "portrait is unsupported");
        rotation.update(270, false, 1400);
        check(rotation.update(90, false, 1600) == -1, "opposite candidates restart stability");
    }

    private static void landscapeTablet() {
        LandscapeRotation rotation = new LandscapeRotation();
        rotation.update(350, true, 0);
        check(rotation.update(10, true, 250) == 0, "natural landscape across the angle wrap");
        rotation.update(180, true, 500);
        check(rotation.update(180, true, 750) == 2, "reverse natural landscape");
        check(rotation.update(90, true, 1000) == -1, "tablet portrait is unsupported");
    }

    private static void readingWidths() {
        int first = ReadingColumns.firstWidth(350, 205, 120);
        check(first >= 205 && 350 - first >= 120, "three-digit range uses spare battery space");
        first = ReadingColumns.firstWidth(350, 240, 100);
        check(first >= 240 && 350 - first >= 100, "decimal range keeps its complete unit");
        first = ReadingColumns.firstWidth(200, 240, 100);
        check(first > 0 && first < 200, "narrow rows share shrinkage proportionally");
        check(Math.abs(first / 240f - (200 - first) / 100f) < .02f, "balanced shrinkage");
        check(ReadingColumns.firstWidth(0, 200, 100) == 0, "unmeasured row");
    }

    private static void locationAccuracy() {
        LocationAccuracyReading accuracy = new LocationAccuracyReading();
        check("—".equals(accuracy.text(0, 10000)), "no GPS accuracy before a fix");
        accuracy.record(4.6f, 1000, 1000);
        check("5 m".equals(accuracy.text(1000, 10000)), "integer meters with a space before the unit");
        accuracy.record(75, 2000, 2000);
        check("75 m".equals(accuracy.text(2000, 10000)), "poor accuracy stays visible even when navigation rejects the fix");
        accuracy.record(3, 1500, 2000);
        accuracy.record(2, 3000, 2000);
        check("75 m".equals(accuracy.text(2000, 10000)), "out-of-order and future samples cannot replace accuracy");
        check("75 m".equals(accuracy.text(12000, 10000)), "freshness boundary");
        check("—".equals(accuracy.text(12001, 10000)), "expired accuracy is hidden");
        accuracy.record(Float.NaN, 13000, 13000);
        check("—".equals(accuracy.text(13000, 10000)), "missing accuracy is unknown");
        accuracy.record(-1, 14000, 14000);
        check("—".equals(accuracy.text(14000, 10000)), "invalid negative accuracy");
        accuracy.record(10, 15000, 15000);
        accuracy.reset();
        check("—".equals(accuracy.text(15000, 10000)), "stopping location clears the old measurement");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
