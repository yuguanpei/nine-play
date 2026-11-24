package com.nova5bike.dashboard;

import java.time.OffsetDateTime;

/** A district observation, kept separate from navigation measurements and app appearance. */
final class WeatherReading {
    static final long REFRESH_MS = 10 * 60_000;
    static final long MAX_AGE_MS = 60 * 60_000;
    final String district, city, districtCode, condition, windDirection;
    final int conditionCode, windScale;
    final float temperature, feelsLike, humidityPercent, windKmh;
    final long observedWallMs, fetchedElapsedMs;

    WeatherReading(String district, String city, String districtCode, String condition, int conditionCode, float temperature,
            float feelsLike, float humidityPercent, String windDirection, int windScale, float windKmh,
            long observedWallMs, long fetchedElapsedMs) {
        this.district = district;
        this.city = city;
        this.districtCode = districtCode;
        this.condition = condition;
        this.conditionCode = conditionCode;
        this.temperature = temperature;
        this.feelsLike = feelsLike;
        this.humidityPercent = humidityPercent;
        this.windDirection = windDirection;
        this.windScale = windScale;
        this.windKmh = windKmh;
        this.observedWallMs = observedWallMs;
        this.fetchedElapsedMs = fetchedElapsedMs;
    }

    boolean validAt(long wallMs) {
        long age = wallMs - observedWallMs;
        return Float.isFinite(temperature) && !condition.isEmpty() && age >= -5 * 60_000 && age <= MAX_AGE_MS;
    }

    String temperatureText() { return Math.round(temperature) + "℃"; }

    String summary() { return temperatureText() + " " + condition; }

    static long observationTime(String value) { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); }

    String wind() {
        if (windScale == 0) return "微风";
        String value = windDirection.isEmpty() ? "风力" : windDirection + "风";
        return windScale >= 0 ? value + windScale + "级" : windDirection.isEmpty() ? "" : value;
    }

    static String windDirection(String compass) {
        String[] codes = {"n", "nne", "ne", "ene", "e", "ese", "se", "sse",
                "s", "ssw", "sw", "wsw", "w", "wnw", "nw", "nnw"};
        String[] names = {"北", "东北偏北", "东北", "东北偏东", "东", "东南偏东", "东南", "东南偏南",
                "南", "西南偏南", "西南", "西南偏西", "西", "西北偏西", "西北", "西北偏北"};
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(compass)) return names[i];
        return "";
    }
}
