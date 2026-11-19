package com.nova5bike.dashboard;

public final class BluetoothInstallTest {
    public static void main(String[] args) {
        if (BikeBluetooth.needsUpdateRecovery(100, 100, 0)
                || !BikeBluetooth.needsUpdateRecovery(100, 200, 0)
                || !BikeBluetooth.needsUpdateRecovery(100, 300, 200)
                || BikeBluetooth.needsUpdateRecovery(100, 300, 300)) {
            throw new AssertionError("Unexpected Bluetooth install recovery policy");
        }
        System.out.println("Bluetooth first install, upgrade and accepted reconnect: passed");
    }
}
