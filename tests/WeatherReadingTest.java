package com.nova5bike.dashboard;

public final class WeatherReadingTest {
    public static void main(String[] args) {
        check(WeatherReading.observationTime("2026-11-23T01:45Z")
                == WeatherReading.observationTime("2026-11-23T01:45:00Z"), "minute-precision provider timestamp");
        check(WeatherReading.observationTime("2026-11-23T09:45+08:00")
                == WeatherReading.observationTime("2026-11-23T01:45Z"), "observation timezone");
        WeatherReading reading = sample(15.06f, "阴", "wsw", 2, 1_000_000);
        check("15℃ 阴".equals(reading.summary()), "temperature is displayed as an integer");
        check("西南偏西风2级".equals(reading.wind()), "wind direction and scale");
        check(reading.validAt(1_000_000), "current observation");
        check(reading.validAt(1_000_000 + WeatherReading.MAX_AGE_MS), "freshness boundary");
        check(!reading.validAt(1_000_001 + WeatherReading.MAX_AGE_MS), "stale weather is hidden");
        check(!reading.validAt(699_999), "future observations are not displayed");
        check(!sample(Float.NaN, "阴", "n", 2, 1_000_000).validAt(1_000_000), "missing temperature");
        check(!sample(15, "", "n", 2, 1_000_000).validAt(1_000_000), "missing weather condition");
        check("微风".equals(sample(15, "晴", "none", 0, 1_000_000).wind()), "calm wind");
        check("风力3级".equals(sample(15, "阴", "vrb", 3, 1_000_000).wind()), "unknown direction retains known scale");
        check("".equals(sample(15, "阴", "vrb", -1, 1_000_000).wind()), "missing wind is omitted");
        check("-2℃ 小雪".equals(sample(-2.3f, "小雪", "n", 2, 1_000_000).summary()), "negative temperatures");
        System.out.println("Weather observation formatting and freshness: passed");
    }

    private static WeatherReading sample(float temperature, String condition, String direction, int scale, long observed) {
        return new WeatherReading("徐汇区", "上海市", "310104", condition, 104, temperature,
                14, 65, WeatherReading.windDirection(direction), scale, 6, observed, 1000);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
