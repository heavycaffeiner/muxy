package com.termux.view;

/** Keeps partial rows only while the device, direction, and scroll destination agree. */
final class PointerScrollAccumulator {
    private float remainder;
    private int device;
    private int destination;
    private boolean active;

    int advance(float rows, int deviceId, int target) {
        if (Float.isNaN(rows) || Float.isInfinite(rows) || rows == 0) return 0;
        if (!active || device != deviceId || destination != target
                || (remainder != 0 && Math.signum(remainder) != Math.signum(rows))) {
            remainder = 0;
        }
        active = true;
        device = deviceId;
        destination = target;
        remainder += Math.max(-120f, Math.min(120f, rows));
        int whole = (int) remainder;
        remainder -= whole;
        return whole;
    }

    void reset() {
        active = false;
        remainder = 0;
    }
}
