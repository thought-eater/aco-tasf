package pe.edu.pucp.tasf.model;

import java.util.*;

/**
 * Representa la red logistica G = (N, A), donde N = aeropuertos y A = vuelos.
 * Provee operaciones de grafo para la busqueda de rutas.
 */
public class LogisticsNetwork {
    private final Map<String, Airport> airports;          // code -> Airport
    private final List<Flight> flights;
    private final Map<String, List<Flight>> outgoing;     // airport code -> outgoing flights

    public LogisticsNetwork() {
        this.airports = new LinkedHashMap<>();
        this.flights = new ArrayList<>();
        this.outgoing = new HashMap<>();
    }

    public void addAirport(Airport airport) {
        airports.put(airport.getCode(), airport);
        outgoing.putIfAbsent(airport.getCode(), new ArrayList<>());
    }

    public void addFlight(Flight flight) {
        flights.add(flight);
        outgoing.computeIfAbsent(flight.getOrigin().getCode(), k -> new ArrayList<>()).add(flight);
    }

    /**
     * Devuelve la siguiente salida concreta de cada horario activo desde un aeropuerto.
     */
    public List<Flight> getActiveFlightsFrom(String airportCode, long minDepartureTimeUtcMinutes) {
        List<Flight> schedules = outgoing.getOrDefault(airportCode, Collections.emptyList());
        List<Flight> result = new ArrayList<>();

        for (Flight schedule : schedules) {
            if (schedule.isCancelled()) {
                continue;
            }
            result.add(schedule.instantiateAfter(minDepartureTimeUtcMinutes));
        }

        result.sort(Comparator.comparingLong(Flight::getDepartureUtcMinutes));
        return result;
    }

    /**
     * Devuelve todos los vuelos activos salientes desde un aeropuerto.
     */
    public List<Flight> getActiveFlightsFrom(String airportCode) {
        return getActiveFlightsFrom(airportCode, 0L);
    }

    /**
     * Encuentra todas las rutas posibles (hasta maxHops) desde origen a destino.
     * Se usa para diagnostico y exploracion.
     */
    public List<List<Flight>> findRoutes(Airport origin, Airport destination,
                                         long startTimeUtcMinutes, int maxHops) {
        List<List<Flight>> result = new ArrayList<>();
        findRoutesRecursive(origin.getCode(), destination.getCode(), startTimeUtcMinutes,
                maxHops, new ArrayList<>(), new HashSet<>(), result);
        return result;
    }

    private void findRoutesRecursive(String current, String target, long currentTimeUtcMinutes,
                                     int hopsLeft, List<Flight> path,
                                     Set<String> visited, List<List<Flight>> result) {
        if (current.equals(target) && !path.isEmpty()) {
            result.add(new ArrayList<>(path));
            return;
        }
        if (hopsLeft == 0) return;

        visited.add(current);
        for (Flight f : getActiveFlightsFrom(current, currentTimeUtcMinutes)) {
            String nextCode = f.getDestination().getCode();
            if (!visited.contains(nextCode)) {
                path.add(f);
                findRoutesRecursive(nextCode, target, f.getArrivalUtcMinutes(),
                        hopsLeft - 1, path, visited, result);
                path.remove(path.size() - 1);
            }
        }
        visited.remove(current);
    }

    /**
     * Cancela un vuelo por ID. Se usa para replanificacion dinamica.
     */
    public void cancelFlight(String flightId) {
        for (Flight f : flights) {
            if (f.getScheduleId().equals(flightId) || f.getId().equals(flightId)) {
                f.setCancelled(true);
                return;
            }
        }
    }

    // Accesores
    public Airport getAirport(String code) { return airports.get(code); }
    public Collection<Airport> getAirports() { return airports.values(); }
    public List<Flight> getFlights() { return Collections.unmodifiableList(flights); }
    public int getAirportCount() { return airports.size(); }
    public int getFlightCount() { return flights.size(); }
}
