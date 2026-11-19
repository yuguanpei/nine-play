package com.nova5bike.dashboard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Route {
    private static final double METERS_PER_DEGREE = 111195;
    private final List<Point> points = new ArrayList<>();
    private final List<Double> distances = new ArrayList<>();
    private final List<Step> steps = new ArrayList<>();
    private double length;
    private double reportedMeters;
    private int duration;
    private double progress;
    private int activeStep;
    private boolean located;

    static Route fromAmap(JSONObject json) throws JSONException {
        Route route = new Route();
        JSONArray steps = json.getJSONArray("steps");
        for (int i = 0; i < steps.length(); i++) {
            JSONObject source = steps.getJSONObject(i);
            double start = route.length;
            for (String value : source.optString("polyline").split(";")) {
                Point point = Point.parse(value);
                if (point != null) route.add(point);
            }
            JSONObject navi = source.optJSONObject("navi");
            String action = navi == null ? "" : navi.optString("action");
            if (action.isEmpty() && navi != null) action = navi.optString("assistant_action");
            JSONObject cost = source.optJSONObject("cost");
            int seconds = cost == null ? 0 : Math.max(0, cost.optInt("duration"));
            route.duration += seconds;
            if (route.length > start) {
                String detail = source.optString("road_name");
                if (detail.isEmpty()) detail = source.optString("instruction");
                route.steps.add(new Step(start, route.length, seconds, action, detail));
            }
        }
        if (route.steps.isEmpty()) throw new JSONException("Missing route geometry");
        route.reportedMeters = json.optDouble("distance", route.length);
        if (!Double.isFinite(route.reportedMeters) || route.reportedMeters <= 0) route.reportedMeters = route.length;
        JSONObject routeCost = json.optJSONObject("cost");
        if (route.duration == 0) {
            route.duration = routeCost == null ? json.optInt("duration") : routeCost.optInt("duration", json.optInt("duration"));
        }
        return route;
    }

    private void add(Point point) {
        if (!points.isEmpty()) {
            double gap = points.get(points.size() - 1).distance(point);
            if (gap < 0.15) return;
            length += gap;
        }
        points.add(point);
        distances.add(length);
    }

    String pathJson() {
        JSONArray path = new JSONArray();
        for (Point point : points) {
            JSONArray pair = new JSONArray();
            pair.put(Double.valueOf(point.lon));
            pair.put(Double.valueOf(point.lat));
            path.put(pair);
        }
        return path.toString();
    }

    double progressMeters() { return progress; }

    Update update(Point position, float accuracy, boolean recovering) {
        double bestScore = Double.POSITIVE_INFINITY;
        double nearest = Double.POSITIVE_INFINITY;
        double closestProgress = progress;
        double lonScale = METERS_PER_DEGREE * Math.cos(Math.toRadians(position.lat));
        for (int i = 1; i < points.size(); i++) {
            double start = distances.get(i - 1);
            double end = distances.get(i);
            if (located && (end < progress - 100 || (!recovering && start > progress + 220))) continue;
            Point a = points.get(i - 1);
            Point b = points.get(i);
            double ax = (a.lon - position.lon) * lonScale;
            double ay = (a.lat - position.lat) * METERS_PER_DEGREE;
            double vx = (b.lon - a.lon) * lonScale;
            double vy = (b.lat - a.lat) * METERS_PER_DEGREE;
            double along = Math.max(0, Math.min(1, -(ax * vx + ay * vy) / (vx * vx + vy * vy)));
            double offset = Math.hypot(ax + along * vx, ay + along * vy);
            double candidate = start + along * (end - start);
            double deviation = Math.abs(candidate - progress);
            double preference = recovering ? Math.min(12, deviation * 0.003) : deviation * 0.025;
            double score = offset + (located ? preference : candidate * 0.005);
            if (score >= bestScore) continue;
            bestScore = score;
            nearest = offset;
            closestProgress = candidate;
        }
        boolean offRoute = nearest > Math.max(38, accuracy * 1.65);
        if (!offRoute) {
            progress = located ? Math.max(progress, closestProgress) : closestProgress;
            located = true;
        }
        while (activeStep + 1 < steps.size() && progress > steps.get(activeStep).end + 7) activeStep++;
        return describe(offRoute, position.distance(points.get(points.size() - 1)) <= 30);
    }

    Update preview() { return describe(false, false); }

    private int currentInstructionIndex() {
        int next = activeStep;
        while (next + 1 < steps.size() && steps.get(next).action.isEmpty()) next++;
        return next;
    }

    private Update describe(boolean offRoute, boolean nearEnd) {
        Step step = steps.get(currentInstructionIndex());
        double remaining = Math.max(0, length - progress);
        // A recent projection may still favor a nearby parallel segment at the finish.
        boolean arrived = located && remaining <= 60 && nearEnd;
        int eta = 0;
        for (int i = activeStep; i < steps.size(); i++) {
            Step timing = steps.get(i);
            double fraction = Math.max(0, (timing.end - Math.max(progress, timing.start))
                    / (timing.end - timing.start));
            eta += Math.round(fraction * timing.seconds);
        }
        if (eta == 0 && remaining > 0 && duration > 0) eta = (int) Math.round(duration * remaining / length);
        String action = step.action.isEmpty() ? "沿路线前进" : step.action;
        String detail = step.road;
        return new Update(reportedMeters - coveredMeters(), Math.max(0, step.end - progress), eta,
                action, detail, offRoute, arrived);
    }

    double totalMeters() {
        return reportedMeters;
    }

    double coveredMeters() {
        return reportedMeters * Math.min(1, progress / length);
    }

    double nextTurnMeters() {
        for (int i = activeStep; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.end >= progress && (step.action.contains("左") || step.action.contains("右")
                    || step.action.contains("掉头") || step.action.contains("环岛"))) return step.end - progress;
        }
        return Double.POSITIVE_INFINITY;
    }

    List<TurnHint> upcomingTurns(int limit) {
        List<TurnHint> hints = new ArrayList<>(limit);
        for (int i = currentInstructionIndex() + 1; i < steps.size() && hints.size() < limit; i++) {
            Step step = steps.get(i);
            if (step.action.isEmpty()) continue;
            hints.add(new TurnHint(Math.max(0, step.end - progress), step.action, step.road));
        }
        return hints;
    }

    static final class TurnHint {
        final double meters;
        final String action;
        final String detail;

        TurnHint(double meters, String action, String detail) {
            this.meters = meters;
            this.action = action;
            this.detail = detail;
        }
    }

    private static final class Step {
        final double start;
        final double end;
        final int seconds;
        final String action;
        final String road;
        Step(double start, double end, int seconds, String action, String road) {
            this.start = start;
            this.end = end;
            this.seconds = seconds;
            this.action = action;
            this.road = road;
        }
    }

    static final class Update {
        final double remaining;
        final double turnMeters;
        final int eta;
        final String action;
        final String detail;
        final boolean offRoute;
        final boolean arrived;
        Update(double remaining, double turnMeters, int eta, String action, String detail,
                boolean offRoute, boolean arrived) {
            this.remaining = remaining;
            this.turnMeters = turnMeters;
            this.eta = eta;
            this.action = action;
            this.detail = detail;
            this.offRoute = offRoute;
            this.arrived = arrived;
        }
    }

    static final class Point {
        final double lon;
        final double lat;

        Point(double lon, double lat) {
            this.lon = lon;
            this.lat = lat;
        }

        static Point validated(double lon, double lat) {
            return Double.isFinite(lon) && Double.isFinite(lat) && Math.abs(lon) <= 180 && Math.abs(lat) <= 90
                    ? new Point(lon, lat) : null;
        }

        static Point parse(String value) {
            int separator = value.indexOf(',');
            if (separator < 1) return null;
            try {
                double lon = Double.parseDouble(value.substring(0, separator));
                double lat = Double.parseDouble(value.substring(separator + 1));
                return validated(lon, lat);
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        String parameter() { return String.format(Locale.US, "%.6f,%.6f", lon, lat); }

        double distance(Point other) {
            double x = (other.lon - lon) * Math.cos(Math.toRadians((lat + other.lat) * 0.5));
            return Math.hypot(x, other.lat - lat) * METERS_PER_DEGREE;
        }
    }
}
