package com.nova5bike.dashboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class VolumeKeysTest {
    public static void main(String[] args) {
        singlePress();
        doublePressSkips();
        doublePressHoldAdjusts();
        slowDoubleIsTwoSingles();
        System.out.println("Volume key gesture recognition: passed");
    }

    private static final class Recorder implements VolumeKeys.Listener {
        final List<String> events = new ArrayList<>();
        public void single(boolean up) { events.add("single:" + up); }
        public void skip(boolean up) { events.add("skip:" + up); }
        public void adjustStart(boolean up) { events.add("adjustStart:" + up); }
        public void adjustTick(boolean up, long dtMs) { events.add("adjustTick:" + up); }
        public void adjustEnd() { events.add("adjustEnd"); }
    }

    private static void singlePress() {
        Recorder r = new Recorder();
        VolumeKeys keys = new VolumeKeys(r);
        keys.down(true, false, 0);
        keys.up(true, 120);
        keys.resolve(120 + 351);
        check(r.events.equals(Arrays.asList("single:true")), "single press fires once after the window: " + r.events);
    }

    private static void doublePressSkips() {
        Recorder r = new Recorder();
        VolumeKeys keys = new VolumeKeys(r);
        keys.down(false, false, 0);
        keys.up(false, 80);
        keys.down(false, false, 200);
        keys.up(false, 320);
        check(r.events.equals(Arrays.asList("skip:false")), "double press must skip: " + r.events);
    }

    private static void doublePressHoldAdjusts() {
        Recorder r = new Recorder();
        VolumeKeys keys = new VolumeKeys(r);
        keys.down(false, false, 0);
        keys.up(false, 80);
        keys.down(false, false, 200);
        keys.down(false, true, 600);
        keys.tick(650);
        keys.up(false, 700);
        check(r.events.equals(Arrays.asList("adjustStart:false", "adjustTick:false", "adjustEnd")),
                "held second press must adjust: " + r.events);
    }

    private static void slowDoubleIsTwoSingles() {
        Recorder r = new Recorder();
        VolumeKeys keys = new VolumeKeys(r);
        keys.down(true, false, 0);
        keys.up(true, 80);
        keys.resolve(80 + 351);
        keys.down(true, false, 500);
        keys.up(true, 600);
        keys.resolve(600 + 351);
        check(r.events.equals(Arrays.asList("single:true", "single:true")), "slow presses are two singles: " + r.events);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
