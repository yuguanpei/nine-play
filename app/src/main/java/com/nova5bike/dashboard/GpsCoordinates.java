package com.nova5bike.dashboard;

final class GpsCoordinates {
    private static final double A = 6378245.0;
    private static final double E2 = 0.00669342162296594323;
    private static final double PI = Math.PI;

    static Route.Point toAmap(double lon, double lat) {
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return new Route.Point(lon, lat);
        double x = lon - 105;
        double y = lat - 35;
        double shared = (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
        double dLat = -100 + 2 * x + 3 * y + .2 * y * y + .1 * x * y + .2 * Math.sqrt(Math.abs(x))
                + shared + (20 * Math.sin(y * PI) + 40 * Math.sin(y * PI / 3)) * 2 / 3
                + (160 * Math.sin(y * PI / 12) + 320 * Math.sin(y * PI / 30)) * 2 / 3;
        double dLon = 300 + x + 2 * y + .1 * x * x + .1 * x * y + .1 * Math.sqrt(Math.abs(x))
                + shared + (20 * Math.sin(x * PI) + 40 * Math.sin(x * PI / 3)) * 2 / 3
                + (150 * Math.sin(x * PI / 12) + 300 * Math.sin(x * PI / 30)) * 2 / 3;
        double rad = Math.toRadians(lat);
        double sin = Math.sin(rad);
        double magic = 1 - E2 * sin * sin;
        double sqrt = Math.sqrt(magic);
        return new Route.Point(lon + dLon * 180 / (A / sqrt * Math.cos(rad) * PI),
                lat + dLat * 180 / (A * (1 - E2) / (magic * sqrt) * PI));
    }
}
