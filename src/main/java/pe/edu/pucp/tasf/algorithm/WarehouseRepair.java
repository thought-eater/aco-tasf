package pe.edu.pucp.tasf.algorithm;

import pe.edu.pucp.tasf.model.Airport;
import pe.edu.pucp.tasf.model.Flight;
import pe.edu.pucp.tasf.model.LogisticsNetwork;
import pe.edu.pucp.tasf.model.RouteAssignment;
import pe.edu.pucp.tasf.model.ShipmentRequest;

import pe.edu.pucp.tasf.model.Solution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Fase de reparacion para soluciones ACO con overflow de almacenes.
 * Intenta rerutear pedidos que contribuyen a los picos de almacen sin romper
 * entregas, plazos ni capacidad de vuelos.
 */
public class WarehouseRepair {
    private final LogisticsNetwork network;
    private final ACOConfig config;
    private final int maxAttempts;
    private final int maxAlternatives;

    public WarehouseRepair(LogisticsNetwork network, ACOConfig config) {
        this.network = network;
        this.config = config;
        this.maxAttempts = Integer.getInteger("tasf.repairAttempts", 80);
        this.maxAlternatives = Integer.getInteger("tasf.repairAlternatives", 8);
    }

    public Solution repair(Solution original) {
        if (original == null || original.getWarehouseOverflow() == 0) {
            return original;
        }

        Solution current = original.copy();
        double bestFitness = current.getFitness();
        int accepted = 0;

        for (int attempt = 0; attempt < maxAttempts && current.getWarehouseOverflow() > 0; attempt++) {
            WarehouseConflict conflict = findWorstConflict(current);
            if (conflict == null || conflict.affectedRoutes().isEmpty()) {
                break;
            }

            boolean improved = false;
            List<Integer> affected = new ArrayList<>(conflict.affectedRoutes());
            affected.sort(Comparator.comparingInt(index ->
                    -current.getRoute(index).getRequest().getQuantity()));

            for (int routeIndex : affected) {
                RouteAssignment currentRoute = current.getRoute(routeIndex);
                ShipmentRequest request = currentRoute.getRequest();
                Map<String, Integer> remainingLoads = flightLoadsExcluding(current, routeIndex);
                List<RouteAssignment> alternatives = findAlternatives(
                        request,
                        remainingLoads,
                        conflict.airportCode(),
                        currentRoute);

                for (RouteAssignment alternative : alternatives) {
                    if (!alternative.isOnTime() || alternative.usesCancelledFlight()) {
                        continue;
                    }

                    RouteAssignment previous = current.getRoute(routeIndex);
                    current.setRoute(routeIndex, alternative);
                    double candidateFitness = current.getFitness();

                    if (candidateFitness < bestFitness
                            && current.getCapacityOverflow() == 0
                            && current.getUndeliveredCount() <= original.getUndeliveredCount()
                            && current.getLateCount() <= original.getLateCount()) {
                        bestFitness = candidateFitness;
                        accepted++;
                        improved = true;
                        break;
                    }

                    current.setRoute(routeIndex, previous);
                }

                if (improved) {
                    break;
                }
            }

            if (!improved) {
                break;
            }
        }

        System.out.printf("Warehouse repair accepted %d changes. Fitness %.2f -> %.2f | Warehouse overflow %d -> %d%n",
                accepted,
                original.getFitness(),
                current.getFitness(),
                original.getWarehouseOverflow(),
                current.getWarehouseOverflow());

        return current.getFitness() < original.getFitness() ? current : original;
    }

    private WarehouseConflict findWorstConflict(Solution solution) {
        Map<String, Airport> airports = new HashMap<>();
        Map<String, List<StockEvent>> eventsByAirport = new HashMap<>();

        for (int routeIndex = 0; routeIndex < solution.size(); routeIndex++) {
            RouteAssignment route = solution.getRoute(routeIndex);
            ShipmentRequest request = route.getRequest();
            int qty = request.getQuantity();

            if (!route.reachesDestination()) {
                addEvent(eventsByAirport, airports, request.getOrigin(),
                        request.getCreationTimeMinutes(), qty, routeIndex);
                addEvent(eventsByAirport, airports, request.getOrigin(),
                        request.getCreationTimeMinutes() + request.getDeadlineMinutes(), -qty, routeIndex);
                continue;
            }

            List<Flight> flights = route.getFlights();
            Flight first = flights.get(0);
            addInterval(eventsByAirport, airports, request.getOrigin(),
                    request.getCreationTimeMinutes(), first.getDepartureUtcMinutes(), qty, routeIndex);

            for (int i = 0; i < flights.size() - 1; i++) {
                Flight current = flights.get(i);
                Flight next = flights.get(i + 1);
                addInterval(eventsByAirport, airports, current.getDestination(),
                        current.getArrivalUtcMinutes(), next.getDepartureUtcMinutes(), qty, routeIndex);
            }
        }

        WarehouseConflict worst = null;
        for (Map.Entry<String, List<StockEvent>> entry : eventsByAirport.entrySet()) {
            Airport airport = airports.get(entry.getKey());
            if (airport == null) {
                continue;
            }

            List<StockEvent> events = entry.getValue();
            events.sort(Comparator
                    .comparingLong(StockEvent::time)
                    .thenComparingInt(StockEvent::delta));

            int currentLoad = 0;
            Set<Integer> activeRoutes = new HashSet<>();
            for (StockEvent event : events) {
                currentLoad += event.delta();
                if (event.delta() > 0) {
                    activeRoutes.add(event.routeIndex());
                } else {
                    activeRoutes.remove(event.routeIndex());
                }

                int overflow = currentLoad - airport.getWarehouseCapacity();
                if (overflow > 0) {
                    WarehouseConflict candidate = new WarehouseConflict(
                            airport.getCode(),
                            event.time(),
                            overflow,
                            new HashSet<>(activeRoutes));
                    if (worst == null || overflow > worst.overflow()) {
                        worst = candidate;
                    }
                }
            }
        }
        return worst;
    }

    private List<RouteAssignment> findAlternatives(ShipmentRequest request,
                                                   Map<String, Integer> committedLoads,
                                                   String congestedAirport,
                                                   RouteAssignment currentRoute) {
        PriorityQueue<PathState> queue = new PriorityQueue<>(Comparator
                .comparingLong(PathState::currentTime)
                .thenComparingInt(state -> state.flights().size()));
        Set<String> originVisited = new HashSet<>();
        originVisited.add(request.getOrigin().getCode());
        queue.add(new PathState(
                request.getOrigin().getCode(),
                request.getCreationTimeMinutes(),
                new ArrayList<>(),
                originVisited));

        List<RouteAssignment> alternatives = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int expansions = 0;
        int maxExpansions = Integer.getInteger("tasf.repairExpansions", 2500);

        while (!queue.isEmpty()
                && alternatives.size() < maxAlternatives
                && expansions++ < maxExpansions) {
            PathState state = queue.poll();
            if (state.currentAirport().equals(request.getDestination().getCode())
                    && !state.flights().isEmpty()) {
                RouteAssignment alternative = new RouteAssignment(request, state.flights());
                if (alternative.isOnTime()
                        && !sameSchedulePath(alternative, currentRoute)
                        && seen.add(pathKey(alternative))) {
                    alternatives.add(alternative);
                }
                continue;
            }

            if (state.flights().size() >= config.getMaxHops()) {
                continue;
            }

            List<Flight> candidates = network.getActiveFlightsFrom(state.currentAirport(), state.currentTime());
            candidates.sort(Comparator
                    .comparing((Flight f) -> !f.getDestination().equals(request.getDestination()))
                    .thenComparing((Flight f) -> f.getDestination().getCode().equals(congestedAirport))
                    .thenComparingLong(Flight::getArrivalUtcMinutes));

            int considered = 0;
            for (Flight flight : candidates) {
                if (++considered > 40) {
                    break;
                }

                String next = flight.getDestination().getCode();
                if (state.visitedAirports().contains(next)) {
                    continue;
                }

                if (next.equals(congestedAirport)
                        && !next.equals(request.getDestination().getCode())) {
                    continue;
                }

                int projectedLoad = committedLoads.getOrDefault(flight.getInstanceId(), 0)
                        + request.getQuantity();
                if (projectedLoad > flight.getCapacity()) {
                    continue;
                }

                if (flight.getArrivalUtcMinutes()
                        > request.getCreationTimeMinutes() + request.getDeadlineMinutes()) {
                    continue;
                }

                List<Flight> nextFlights = new ArrayList<>(state.flights());
                nextFlights.add(flight);
                Set<String> nextVisited = new HashSet<>(state.visitedAirports());
                nextVisited.add(next);
                queue.add(new PathState(next, flight.getArrivalUtcMinutes(), nextFlights, nextVisited));
            }
        }

        return alternatives;
    }

    private Map<String, Integer> flightLoadsExcluding(Solution solution, int excludedRouteIndex) {
        Map<String, Integer> loads = new HashMap<>();
        for (int i = 0; i < solution.size(); i++) {
            if (i == excludedRouteIndex) {
                continue;
            }
            RouteAssignment route = solution.getRoute(i);
            int qty = route.getRequest().getQuantity();
            for (Flight flight : route.getFlights()) {
                loads.merge(flight.getInstanceId(), qty, Integer::sum);
            }
        }
        return loads;
    }

    private boolean sameSchedulePath(RouteAssignment a, RouteAssignment b) {
        return pathKey(a).equals(pathKey(b));
    }

    private String pathKey(RouteAssignment route) {
        StringBuilder key = new StringBuilder();
        for (Flight flight : route.getFlights()) {
            if (!key.isEmpty()) {
                key.append('|');
            }
            key.append(flight.getInstanceId());
        }
        return key.toString();
    }

    private void addInterval(Map<String, List<StockEvent>> eventsByAirport,
                             Map<String, Airport> airports,
                             Airport airport,
                             long start,
                             long end,
                             int qty,
                             int routeIndex) {
        if (airport == null || end <= start) {
            return;
        }
        addEvent(eventsByAirport, airports, airport, start, qty, routeIndex);
        addEvent(eventsByAirport, airports, airport, end, -qty, routeIndex);
    }

    private void addEvent(Map<String, List<StockEvent>> eventsByAirport,
                          Map<String, Airport> airports,
                          Airport airport,
                          long time,
                          int delta,
                          int routeIndex) {
        airports.putIfAbsent(airport.getCode(), airport);
        eventsByAirport.computeIfAbsent(airport.getCode(), key -> new ArrayList<>())
                .add(new StockEvent(time, delta, routeIndex));
    }

    private record StockEvent(long time, int delta, int routeIndex) { }

    private record WarehouseConflict(
            String airportCode,
            long time,
            int overflow,
            Set<Integer> affectedRoutes) { }

    private record PathState(
            String currentAirport,
            long currentTime,
            List<Flight> flights,
            Set<String> visitedAirports) { }
}
