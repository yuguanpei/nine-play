package com.nova5bike.dashboard;

public final class BluetoothInstallTest {
    public static void main(String[] args) {
        if (BikeBluetooth.needsUpdateRecovery(100, 100, 0)
                || !BikeBluetooth.needsUpdateRecovery(100, 200, 0)
                || !BikeBluetooth.needsUpdateRecovery(100, 300, 200)
                || BikeBluetooth.needsUpdateRecovery(100, 300, 300)) {
            throw new AssertionError("Unexpected Bluetooth install recovery policy");
        }
        // Repeated failures must keep retrying at a bounded, nondecreasing cadence, including
        // immediately after an APK update. The system needs a quiet interval to release its GATT client.
        for (boolean afterUpdate : new boolean[]{false, true}) {
            long previous = 0;
            for (int failures = 0; failures < 100; failures++) {
                long delay = BikeBluetooth.retryDelayMs(failures, afterUpdate);
                if (delay < previous || delay < (afterUpdate ? 15_000 : 2_000) || delay > 30_000) {
                    throw new AssertionError("Unsafe Bluetooth retry cadence at failure " + failures);
                }
                previous = delay;
            }
            if (previous != 30_000) throw new AssertionError("Retry backoff did not reach its cap");
        }
        System.out.println("Bluetooth install recovery and bounded retry backoff: passed");
    }
}
