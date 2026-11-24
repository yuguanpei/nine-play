package com.nova5bike.dashboard;

/** Shares spare row width while reserving enough room for each complete reading. */
final class ReadingColumns {
    static int firstWidth(int available, float first, float second) {
        if (available <= 0) return 0;
        float needed = first + second;
        float width = needed <= available ? first + (available - needed) / 2
                : available * first / Math.max(1, needed);
        return Math.max(0, Math.min(available, Math.round(width)));
    }
}
