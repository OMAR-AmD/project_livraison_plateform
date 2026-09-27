package com.delivery.backend.modules.delivery;

import java.time.LocalTime;

/**
 * The platform's single, shared view of road congestion.
 *
 * <p>This rule used to be written twice — once in {@link PricingService} to decide
 * what a customer is quoted, and once in {@link RouteOptimizationService} to
 * decide what the solver is allowed to plan. The two copies happened to agree, but
 * that was luck rather than design: if either had been edited, pricing and
 * dispatch would have disagreed about what "45 minutes" means, and the symptom
 * would be couriers being assigned rounds that break the quote the customer was
 * given. Both now read the factor from here so that cannot happen.
 *
 * <p>The model is deliberately crude — two rush-hour windows with a flat
 * multiplier. It is a placeholder for real traffic data, and it is applied
 * consistently rather than accurately.
 */
public final class TrafficModel {

    /** Multiplier applied during the morning and evening peaks. */
    public static final double RUSH_HOUR_FACTOR = 1.4;

    /** Multiplier applied at all other times. */
    public static final double FREE_FLOW_FACTOR = 1.0;

    private TrafficModel() {}

    /**
     * Congestion multiplier for the current wall-clock time.
     *
     * <p>Note this is evaluated on the server's local clock, which is a known
     * simplification: a multi-region deployment would need the operating area's
     * timezone rather than the host's.
     */
    public static double currentFactor() {
        return factorAt(LocalTime.now());
    }

    /** Congestion multiplier for a given time of day. Package-visible for tests. */
    static double factorAt(LocalTime time) {
        int hour = time.getHour();
        boolean morningPeak = hour >= 7 && hour <= 9;
        boolean eveningPeak = hour >= 17 && hour <= 19;
        return (morningPeak || eveningPeak) ? RUSH_HOUR_FACTOR : FREE_FLOW_FACTOR;
    }

    /** True when {@code durationSeconds} is inflated by peak-hour congestion. */
    public static boolean isRushHour() {
        return currentFactor() > FREE_FLOW_FACTOR;
    }
}
