package com.nova5bike.dashboard;

public final class NavigationPlanTest {
    public static void main(String[] args) {
        NavigationPlan plan = new NavigationPlan();
        plan.queue();
        check(plan.waiting() && plan.resumable(), "waiting for first fix");
        int first = plan.start();
        check(plan.finish(first, false) && plan.failed() && !plan.resumable(), "failed trip stays manual");
        plan.queue();
        int retry = plan.start();
        check(plan.finish(retry, true) && plan.resumable(), "retry starts navigation");

        int oldMode = plan.start();
        plan.queue();
        int newMode = plan.start();
        check(!plan.finish(oldMode, true) && plan.planning(), "ignore old mode response");
        check(plan.finish(newMode, true) && !plan.failed(), "existing route stays usable after replan");
        int beforeGap = plan.start();
        plan.keepExistingRoute();
        check(!plan.finish(beforeGap, true) && plan.resumable(), "GPS recovery discards stale reroute");
        int canceled = plan.start();
        plan.clear();
        check(!plan.finish(canceled, true) && !plan.resumable(), "cancel defeats late response");
        System.out.println("Navigation failure, retry, route replacement and cancellation: passed");
    }

    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
    }
}
