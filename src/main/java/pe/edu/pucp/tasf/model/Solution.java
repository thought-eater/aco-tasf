package pe.edu.pucp.tasf.model;

import pe.edu.pucp.tasf.util.TimeUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Representa una solucion completa: un conjunto de rutas asignadas para
 * todas las solicitudes de envio.
 * El objetivo es minimizar el numero de maletas que exceden su plazo.
 */
public class Solution {
    private final List<RouteAssignment> routes;
    private Metrics metrics;

    public Solution() {
        this.routes = new ArrayList<>();
    }

    public Solution(List<RouteAssignment> routes) {
        this.routes = new ArrayList<>(routes);
    }

    public void addRoute(RouteAssignment route) {
        this.routes.add(route);
        this.metrics = null;
    }

    /**
     * Objetivo principal: numero total de maletas entregadas tarde.
     */
    public int getLateCount() {
        return metrics().lateCount();
    }

    public int getUndeliveredCount() {
        return metrics().undeliveredCount();
    }

    /**
     * Retraso total acumulado en todos los envios (metrica secundaria).
     */
    public double getTotalDelay() {
        return TimeUtil.minutesToDays(metrics().totalDelayMinutes());
    }

    /**
     * Numero total de maletas en todas las solicitudes.
     */
    public int getTotalSuitcases() {
        return metrics().totalSuitcases();
    }

    public int getDeliveredCount() {
        return metrics().deliveredSuitcases();
    }

    public int getCapacityOverflow() {
        return metrics().capacityOverflow();
    }

    public int getWarehouseOverflow() {
        return metrics().warehouseOverflow();
    }

    /**
     * Fitness: mientras menor, mejor.
     * Combina maletas tardias (principal) con retraso total (desempate)
     * y agrega penalizaciones por inviabilidad de capacidad o cancelaciones.
     */
    public double getFitness() {
        return metrics().fitness();
    }

    /**
     * Copia profunda de esta solucion.
     */
    public Solution copy() {
        List<RouteAssignment> copied = new ArrayList<>();
        for (RouteAssignment ra : routes) {
            copied.add(ra.copy());
        }
        return new Solution(copied);
    }

    // Accesores
    public List<RouteAssignment> getRoutes() { return Collections.unmodifiableList(routes); }
    public int size() { return routes.size(); }

    public RouteAssignment getRoute(int index) { return routes.get(index); }

    public void setRoute(int index, RouteAssignment route) {
        routes.set(index, route);
        this.metrics = null;
    }

    @Override
    public String toString() {
        return String.format(
                "Solution [requests=%d, suitcases=%d, delivered=%d, late=%d, undelivered=%d, fitness=%.2f]",
                routes.size(), getTotalSuitcases(), getDeliveredCount(),
                getLateCount(), getUndeliveredCount(), getFitness());
    }

    private Metrics metrics() {
        if (metrics == null) {
            metrics = computeMetrics();
        }
        return metrics;
    }

    private Metrics computeMetrics() {
        final double penaltyUndelivered = Double.parseDouble(
                System.getProperty("tasf.penaltyUndelivered", "20000.0"));
        final double penaltyLate = Double.parseDouble(
                System.getProperty("tasf.penaltyLate", "1000.0"));
        final double penaltyDelay = Double.parseDouble(
                System.getProperty("tasf.penaltyDelay", "100.0"));
        final double penaltyCancelled = Double.parseDouble(
                System.getProperty("tasf.penaltyCancelled", "20000.0"));
        final double penaltyCapacity = Double.parseDouble(
                System.getProperty("tasf.penaltyCapacity", "5000.0"));
        final double penaltyWarehouse = Double.parseDouble(
                System.getProperty("tasf.penaltyWarehouse", "500.0"));

        int lateCount = 0;
        int undeliveredCount = 0;
        int deliveredSuitcases = 0;
        int totalSuitcases = 0;
        long totalDelayMinutes = 0;
        double fitness = 0.0;

        Map<String, Integer> flightLoads = new HashMap<>();
        Map<String, Integer> flightCapacity = new HashMap<>();
        Map<String, Airport> airportsByCode = new HashMap<>();
        Map<String, List<StockEvent>> stockEvents = new HashMap<>();

        for (RouteAssignment route : routes) {
            ShipmentRequest request = route.getRequest();
            int qty = request.getQuantity();
            totalSuitcases += qty;

            if (!route.reachesDestination()) {
                undeliveredCount += qty;
                lateCount += qty;
                fitness += penaltyUndelivered * qty;
                addInterval(stockEvents, airportsByCode,
                        request.getOrigin(),
                        request.getCreationTimeMinutes(),
                        request.getCreationTimeMinutes() + request.getDeadlineMinutes(),
                        qty);
                continue;
            }

            deliveredSuitcases += qty;

            if (!route.isOnTime()) {
                lateCount += qty;
                totalDelayMinutes += route.getDelayMinutes() * qty;
                fitness += penaltyLate * qty;
                fitness += penaltyDelay * route.getDelay() * qty;
            }

            if (route.usesCancelledFlight()) {
                fitness += penaltyCancelled * qty;
            }

            List<Flight> flights = route.getFlights();
            for (Flight flight : flights) {
                flightLoads.merge(flight.getInstanceId(), qty, Integer::sum);
                flightCapacity.putIfAbsent(flight.getInstanceId(), flight.getCapacity());
            }

            Flight first = flights.get(0);
            addInterval(stockEvents, airportsByCode,
                    request.getOrigin(),
                    request.getCreationTimeMinutes(),
                    first.getDepartureUtcMinutes(),
                    qty);

            for (int i = 0; i < flights.size() - 1; i++) {
                Flight current = flights.get(i);
                Flight next = flights.get(i + 1);
                addInterval(stockEvents, airportsByCode,
                        current.getDestination(),
                        current.getArrivalUtcMinutes(),
                        next.getDepartureUtcMinutes(),
                        qty);
            }
        }

        int capacityOverflow = 0;
        for (Map.Entry<String, Integer> entry : flightLoads.entrySet()) {
            int load = entry.getValue();
            int cap = flightCapacity.getOrDefault(entry.getKey(), 0);
            capacityOverflow += Math.max(0, load - cap);
        }

        int warehouseOverflow = 0;
        for (Map.Entry<String, List<StockEvent>> entry : stockEvents.entrySet()) {
            Airport airport = airportsByCode.get(entry.getKey());
            if (airport == null) {
                continue;
            }
            List<StockEvent> events = entry.getValue();
            events.sort(Comparator
                    .comparingLong(StockEvent::time)
                    .thenComparingInt(StockEvent::delta));

            int current = 0;
            int peak = 0;
            for (StockEvent event : events) {
                current += event.delta();
                peak = Math.max(peak, current);
            }
            warehouseOverflow += Math.max(0, peak - airport.getWarehouseCapacity());
        }

        fitness += penaltyCapacity * capacityOverflow;
        fitness += penaltyWarehouse * warehouseOverflow;

        return new Metrics(
                lateCount,
                undeliveredCount,
                deliveredSuitcases,
                totalSuitcases,
                totalDelayMinutes,
                capacityOverflow,
                warehouseOverflow,
                fitness
        );
    }

    private void addInterval(Map<String, List<StockEvent>> stockEvents,
                             Map<String, Airport> airportsByCode,
                             Airport airport,
                             long start,
                             long end,
                             int qty) {
        if (airport == null || end <= start) {
            return;
        }
        airportsByCode.putIfAbsent(airport.getCode(), airport);
        List<StockEvent> events = stockEvents.computeIfAbsent(airport.getCode(), key -> new ArrayList<>());
        events.add(new StockEvent(start, qty));
        events.add(new StockEvent(end, -qty));
    }

    private record StockEvent(long time, int delta) { }

    private record Metrics(
            int lateCount,
            int undeliveredCount,
            int deliveredSuitcases,
            int totalSuitcases,
            long totalDelayMinutes,
            int capacityOverflow,
            int warehouseOverflow,
            double fitness
    ) { }
}
