package pe.edu.pucp.tasf.model;

import pe.edu.pucp.tasf.util.TimeUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Representa la ruta planificada para un envio: una secuencia de vuelos
 * que transporta maletas desde el origen hasta el destino.
 * Una ruta puede ser directa o con escalas intermedias.
 */
public class RouteAssignment {
    private final ShipmentRequest request;
    private final List<Flight> flights;  // ordered sequence of flights

    public RouteAssignment(ShipmentRequest request) {
        this.request = request;
        this.flights = new ArrayList<>();
    }

    public RouteAssignment(ShipmentRequest request, List<Flight> flights) {
        this.request = request;
        this.flights = new ArrayList<>(flights);
    }

    public void addFlight(Flight flight) {
        this.flights.add(flight);
    }

    /**
     * Calcula el tiempo total de transito de esta ruta.
     */
    public double getTotalTransitTime() {
        if (!reachesDestination()) return 999.0;
        return TimeUtil.minutesToDays(getTotalTransitMinutes());
    }

    public long getTotalTransitMinutes() {
        if (!reachesDestination()) return Long.MAX_VALUE / 4;
        return Math.max(0L, getArrivalTimeMinutes() - request.getCreationTimeMinutes());
    }

    /**
     * Devuelve el tiempo estimado de llegada al destino final.
     */
    public double getArrivalTime() {
        if (!reachesDestination()) return 999.0;
        return TimeUtil.minutesToDays(getArrivalTimeMinutes());
    }

    public long getArrivalTimeMinutes() {
        if (!reachesDestination()) return Long.MAX_VALUE / 4;
        return flights.get(flights.size() - 1).getArrivalUtcMinutes();
    }

    /**
     * Verifica si esta ruta entrega dentro del plazo.
     */
    public boolean isOnTime() {
        return reachesDestination() && getTotalTransitMinutes() <= request.getDeadlineMinutes();
    }

    /**
     * Calcula el retraso por encima del plazo. Devuelve 0 si llega a tiempo.
     */
    public double getDelay() {
        return TimeUtil.minutesToDays(getDelayMinutes());
    }

    public long getDelayMinutes() {
        if (!reachesDestination()) return request.getDeadlineMinutes();
        return Math.max(0L, getTotalTransitMinutes() - request.getDeadlineMinutes());
    }

    /**
     * Verifica si la ruta alcanza el destino solicitado.
     */
    public boolean reachesDestination() {
        return !flights.isEmpty()
                && flights.get(flights.size() - 1).getDestination().equals(request.getDestination());
    }

    public boolean usesCancelledFlight() {
        for (Flight f : flights) {
            if (f.isCancelled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evalua la factibilidad a nivel de ruta excluyendo conflictos de capacidad
     * compartida, que se miden globalmente en {@link Solution}.
     */
    public boolean isFeasible() {
        return reachesDestination() && !usesCancelledFlight();
    }

    /**
     * Copia profunda de esta asignacion de ruta.
     */
    public RouteAssignment copy() {
        return new RouteAssignment(request, new ArrayList<>(flights));
    }

    // Accesores
    public ShipmentRequest getRequest() { return request; }
    public List<Flight> getFlights() { return Collections.unmodifiableList(flights); }
    public int getHopCount() { return flights.size(); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(request.getId()).append(": ");
        if (flights.isEmpty()) {
            sb.append(request.getOrigin().getCode()).append(" -> ").append(request.getDestination().getCode());
            sb.append(" [UNASSIGNED]");
            return sb.toString();
        }
        for (int i = 0; i < flights.size(); i++) {
            Flight f = flights.get(i);
            if (i == 0) sb.append(f.getOrigin().getCode());
            sb.append(" --(").append(f.getScheduleId()).append(")--> ").append(f.getDestination().getCode());
        }
        sb.append(String.format(" [transit=%.2f, deadline=%.1f, %s]",
                getTotalTransitTime(), request.getDeadline(),
                !reachesDestination() ? "UNASSIGNED" : (isOnTime() ? "ON_TIME" : "DELAYED")));
        return sb.toString();
    }
}
