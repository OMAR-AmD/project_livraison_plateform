package com.delivery.backend.modules.delivery;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Service
public class RouteOptimizationService {

    static {
        try {
            Loader.loadNativeLibraries();
        } catch (Exception e) {
            System.err.println("Failed to load OR-Tools native libraries. Ensure ortools-java is correctly configured.");
            e.printStackTrace();
        }
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String osrmTableApi;

    // `application.`, not `app.` -- see PricingService for why the mismatch
    // between this @Value and the yml key went unnoticed for so long.
    public RouteOptimizationService(@Value("${application.osrm.base-url:http://localhost:5000}") String osrmBaseUrl) {
        // Explicit timeouts: this call sits inside the auto-dispatch loop, which runs
        // once per candidate courier. Without them a stalled OSRM blocks the caller
        // indefinitely and the surrounding transaction stays open.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
        this.osrmTableApi = osrmBaseUrl + "/table/v1/driving/";
    }

    public RouteOptimizationResponse optimizeDeliveries(double courierLat, double courierLng, List<Delivery> requestedDeliveries) {
        if (requestedDeliveries == null || requestedDeliveries.isEmpty()) {
            return new RouteOptimizationResponse();
        }

        // Only deliveries with real coordinates can be routed. This used to substitute
        // randomly generated points around Casablanca for any missing coordinate, which
        // meant the solver was optimising a route that bore no relation to the one the
        // customer and the driver would actually travel. Legacy rows created before
        // coordinates became mandatory are now skipped rather than invented.
        List<Delivery> deliveries = requestedDeliveries.stream()
                .filter(RouteOptimizationService::hasUsableCoordinates)
                .toList();

        if (deliveries.isEmpty()) {
            throw new IllegalArgumentException("No delivery in this route has usable GPS coordinates.");
        }

        int numDeliveries = deliveries.size();
        int numNodes = 1 + (numDeliveries * 2); // 1 for Courier (Depot), 2 for each delivery (Pickup + Dropoff)

        // Gather coordinates for OSRM
        // Node 0: Courier
        // Node 1 to N: Pickups
        // Node N+1 to 2N: Dropoffs
        List<double[]> coords = new ArrayList<>();
        coords.add(new double[]{courierLng, courierLat}); // OSRM takes Lng,Lat

        for (Delivery d : deliveries) {
            coords.add(new double[]{d.getPickupLng(), d.getPickupLat()});
        }
        for (Delivery d : deliveries) {
            coords.add(new double[]{d.getDropoffLng(), d.getDropoffLat()});
        }

        // Fetch Time Matrix from OSRM
        long[][] timeMatrix = fetchTimeMatrix(coords);
        if (timeMatrix == null) {
            throw new RuntimeException("Failed to fetch time matrix from OSRM");
        }

        // OR-Tools Setup
        RoutingIndexManager manager = new RoutingIndexManager(numNodes, 1, 0);
        RoutingModel routing = new RoutingModel(manager);

        // Transit Callback
        final int transitCallbackIndex = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
            int fromNode = manager.indexToNode(fromIndex);
            int toNode = manager.indexToNode(toIndex);
            return timeMatrix[fromNode][toNode];
        });
        routing.setArcCostEvaluatorOfAllVehicles(transitCallbackIndex);

        // Time Dimension
        routing.addDimension(
                transitCallbackIndex,
                0, // no slack
                14400, // vehicle max total time (e.g., 4 hours = 14400 seconds)
                true, // start cumul to zero
                "Time"
        );
        RoutingDimension timeDimension = routing.getMutableDimension("Time");

        // Define Pickups and Deliveries constraints
        Solver solver = routing.solver();
        long penalty = 100000; // Massive penalty to force picking up

        for (int i = 0; i < numDeliveries; i++) {
            long pickupIndex = manager.nodeToIndex(i + 1);
            long dropoffIndex = manager.nodeToIndex(i + 1 + numDeliveries);

            // Pickup before Dropoff
            routing.addPickupAndDelivery(pickupIndex, dropoffIndex);
            solver.addConstraint(solver.makeEquality(routing.vehicleVar(pickupIndex), routing.vehicleVar(dropoffIndex)));
            solver.addConstraint(solver.makeLessOrEqual(timeDimension.cumulVar(pickupIndex), timeDimension.cumulVar(dropoffIndex)));

            // Max Ride Time Constraint: Dropoff Time - Pickup Time <= 2700 seconds (45 mins)
            solver.addConstraint(solver.makeLessOrEqual(
                    solver.makeDifference(timeDimension.cumulVar(dropoffIndex), timeDimension.cumulVar(pickupIndex)),
                    2700
            ));

            // Make the delivery optional but highly prioritized
            routing.addDisjunction(new long[]{pickupIndex}, penalty);
            routing.addDisjunction(new long[]{dropoffIndex}, penalty);
        }

        // Set search parameters
        RoutingSearchParameters searchParameters =
                main.defaultRoutingSearchParameters()
                        .toBuilder()
                        .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PARALLEL_CHEAPEST_INSERTION)
                        .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                        .setTimeLimit(com.google.protobuf.Duration.newBuilder().setSeconds(2).build())
                        .build();

        // Solve
        Assignment solution = routing.solveWithParameters(searchParameters);

        if (solution == null) {
            throw new RuntimeException("No solution found by OR-Tools.");
        }

        return formatSolution(manager, routing, solution, deliveries);
    }

    private static boolean hasUsableCoordinates(Delivery d) {
        return d.getPickupLat() != null && d.getPickupLng() != null
                && d.getDropoffLat() != null && d.getDropoffLng() != null;
    }

    private long[][] fetchTimeMatrix(List<double[]> coords) {
        StringBuilder urlBuilder = new StringBuilder(osrmTableApi);
        for (int i = 0; i < coords.size(); i++) {
            urlBuilder.append(coords.get(i)[0]).append(",").append(coords.get(i)[1]);
            if (i < coords.size() - 1) {
                urlBuilder.append(";");
            }
        }
        urlBuilder.append("?annotations=duration");

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(urlBuilder.toString(), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode durations = root.path("durations");
            
            int size = coords.size();
            long[][] matrix = new long[size][size];
            
            double congestionFactor = TrafficModel.currentFactor();

            for (int i = 0; i < size; i++) {
                JsonNode row = durations.get(i);
                for (int j = 0; j < size; j++) {
                    double rawDuration = row.get(j).asDouble();
                    matrix[i][j] = (long) Math.ceil(rawDuration * congestionFactor);
                }
            }
            return matrix;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private RouteOptimizationResponse formatSolution(RoutingIndexManager manager, RoutingModel routing, Assignment solution, List<Delivery> deliveries) {
        RouteOptimizationResponse response = new RouteOptimizationResponse();
        long index = routing.start(0);
        
        List<String> routeLog = new ArrayList<>();
        long routeDistance = 0;

        int stepCounter = 1;
        while (!routing.isEnd(index)) {
            int node = manager.indexToNode(index);
            if (node == 0) {
                routeLog.add("Start (Courier Location)");
            } else if (node <= deliveries.size()) {
                Delivery d = deliveries.get(node - 1);
                routeLog.add("Pickup: " + d.getDescription());
                response.getOrderedWaypoints().add(new Waypoint(d.getId(), "PICKUP",
                        d.getPickupLat(), d.getPickupLng(), d.getDescription(), stepCounter++));
            } else {
                Delivery d = deliveries.get(node - 1 - deliveries.size());
                routeLog.add("Dropoff: " + d.getDescription());
                response.getOrderedWaypoints().add(new Waypoint(d.getId(), "DROPOFF",
                        d.getDropoffLat(), d.getDropoffLng(), d.getDescription(), stepCounter++));
            }

            long previousIndex = index;
            index = solution.value(routing.nextVar(index));
            routeDistance += routing.getArcCostForVehicle(previousIndex, index, 0);
        }
        
        routeLog.add("End");
        response.setTotalTimeSeconds(routeDistance);
        response.setRouteLog(routeLog);
        
        return response;
    }
}
