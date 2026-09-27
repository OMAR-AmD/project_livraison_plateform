package com.delivery.backend.modules.delivery;

import java.util.ArrayList;
import java.util.List;

public class RouteOptimizationResponse {
    private long totalTimeSeconds;
    private List<String> routeLog = new ArrayList<>();
    private List<Waypoint> orderedWaypoints = new ArrayList<>();

    public RouteOptimizationResponse() {}

    public long getTotalTimeSeconds() {
        return totalTimeSeconds;
    }

    public void setTotalTimeSeconds(long totalTimeSeconds) {
        this.totalTimeSeconds = totalTimeSeconds;
    }

    public List<String> getRouteLog() {
        return routeLog;
    }

    public void setRouteLog(List<String> routeLog) {
        this.routeLog = routeLog;
    }

    public List<Waypoint> getOrderedWaypoints() {
        return orderedWaypoints;
    }

    public void setOrderedWaypoints(List<Waypoint> orderedWaypoints) {
        this.orderedWaypoints = orderedWaypoints;
    }
}
