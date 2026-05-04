package pe.edu.pucp.tasf.algorithm;

import pe.edu.pucp.tasf.model.Flight;
import pe.edu.pucp.tasf.model.LogisticsNetwork;
import pe.edu.pucp.tasf.model.RouteAssignment;
import pe.edu.pucp.tasf.model.ShipmentRequest;
import pe.edu.pucp.tasf.model.Solution;
import pe.edu.pucp.tasf.util.TimeUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Representa una hormiga artificial que construye una solucion completa
 * eligiendo vuelos de manera probabilistica guiada por feromonas
 * e informacion heuristica.
 *
 * Cada hormiga arma rutas para todas las solicitudes de envio
 * usando la probabilidad de transicion de ACO:
 *
 *   p(i,j) = [tau(i,j)]^alpha * [eta(i,j)]^beta / sum_k([tau(i,k)]^alpha * [eta(i,k)]^beta)
 *
 * donde tau = feromona y eta = deseabilidad heuristica.
 */
public class Ant {
    private static final long STOCK_BUCKET_MINUTES = 60L;
    private static final boolean WAREHOUSE_AWARE =
            Boolean.parseBoolean(System.getProperty("tasf.warehouseAware", "false"));

    private final LogisticsNetwork network;
    private final PheromoneMatrix pheromones;
    private final ACOConfig config;
    private final Random random;

    private Solution solution;

    public Ant(LogisticsNetwork network, PheromoneMatrix pheromones,
               ACOConfig config, Random random) {
        this.network = network;
        this.pheromones = pheromones;
        this.config = config;
        this.random = random;
    }

    /**
     * Construye una solucion completa armando una ruta por solicitud.
     * Las solicitudes se procesan cronologicamente para que la demanda
     * temprana compita de forma justa por las mismas salidas concretas.
     */
    public Solution constructSolution(List<ShipmentRequest> requests) {
        solution = new Solution();
        Map<String, Integer> committedLoads = new HashMap<>();
        Map<String, Map<Long, Integer>> committedStock = new HashMap<>();

        List<ShipmentRequest> ordered = new ArrayList<>(requests);
        ordered.sort(Comparator
                .comparingLong(ShipmentRequest::getCreationTimeMinutes)
                .thenComparing(ShipmentRequest::getId));

        for (ShipmentRequest req : ordered) {
            RouteAssignment route = buildRoute(req, committedLoads, committedStock);
            solution.addRoute(route);
        }

        return solution;
    }

    /**
     * Construye una ruta para una sola solicitud usando seleccion
     * probabilistica de vuelos guiada por feromona y heuristica.
     */
    private RouteAssignment buildRoute(ShipmentRequest req,
                                       Map<String, Integer> committedLoads,
                                       Map<String, Map<Long, Integer>> committedStock) {
        String currentCode = req.getOrigin().getCode();
        String targetCode = req.getDestination().getCode();
        long currentTime = req.getCreationTimeMinutes();

        List<Flight> chosenFlights = new ArrayList<>();
        Set<String> visitedAirports = new HashSet<>();
        visitedAirports.add(currentCode);
        Map<String, Integer> tentativeLoads = new HashMap<>();
        Map<String, Map<Long, Integer>> tentativeStock = new HashMap<>();

        for (int hop = 0; hop < config.getMaxHops(); hop++) {
            if (currentCode.equals(targetCode)) break;

            // Obtener vuelos disponibles desde el aeropuerto actual
            List<Flight> candidates = network.getActiveFlightsFrom(currentCode, currentTime);

            // Filtrar: no visitados, con capacidad de vuelo y almacen disponible durante la espera.
            List<Flight> feasible = new ArrayList<>();
            List<Flight> warehouseConstrained = new ArrayList<>();
            List<Flight> overloaded = new ArrayList<>();
            for (Flight f : candidates) {
                String nextCode = f.getDestination().getCode();
                if (visitedAirports.contains(nextCode)) {
                    continue;
                }
                int projectedLoad = committedLoads.getOrDefault(f.getInstanceId(), 0)
                        + tentativeLoads.getOrDefault(f.getInstanceId(), 0)
                        + req.getQuantity();
                if (projectedLoad <= f.getCapacity()) {
                    int warehouseOverflow = projectedWarehouseOverflow(
                            currentCode,
                            currentTime,
                            f.getDepartureUtcMinutes(),
                            req.getQuantity(),
                            committedStock,
                            tentativeStock);
                    if (!WAREHOUSE_AWARE || warehouseOverflow == 0) {
                        feasible.add(f);
                    } else {
                        warehouseConstrained.add(f);
                    }
                } else {
                    overloaded.add(f);
                }
            }

            if (feasible.isEmpty()) {
                feasible.addAll(warehouseConstrained);
            }

            if (feasible.isEmpty()) {
                feasible.addAll(overloaded);
            }

            if (feasible.isEmpty()) break; // no hay salida factible y la ruta queda incompleta

            // Seleccionar el siguiente vuelo de forma probabilistica
            Flight chosen = selectFlight(feasible, req, currentTime);
            chosenFlights.add(chosen);
            tentativeLoads.merge(chosen.getInstanceId(), req.getQuantity(), Integer::sum);
            addStockInterval(tentativeStock, currentCode, currentTime,
                    chosen.getDepartureUtcMinutes(), req.getQuantity());

            currentCode = chosen.getDestination().getCode();
            currentTime = chosen.getArrivalUtcMinutes();
            visitedAirports.add(currentCode);
        }

        if (!currentCode.equals(targetCode)) {
            return new RouteAssignment(req);
        }

        for (Map.Entry<String, Integer> entry : tentativeLoads.entrySet()) {
            committedLoads.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        mergeStock(committedStock, tentativeStock);
        return new RouteAssignment(req, chosenFlights);
    }

    /**
     * Selecciona un vuelo usando la probabilidad de transicion de ACO.
     *
     * Combina feromona (tau^alpha) con deseabilidad heuristica (eta^beta):
     * - eta considera tiempo de viaje y cercania al destino
     * - los vuelos directos al destino reciben un bono
     */
    private Flight selectFlight(List<Flight> candidates, ShipmentRequest req, long currentTime) {
        double[] probabilities = new double[candidates.size()];
        double sum = 0;

        for (int i = 0; i < candidates.size(); i++) {
            Flight f = candidates.get(i);

            // Factor de feromona: tau^alpha
            double tau = pheromones.getPheromoneForFlight(f);
            double tauFactor = Math.pow(tau, config.getAlpha());

            // Factor heuristico: eta^beta
            double eta = computeHeuristic(f, req, currentTime);
            double etaFactor = Math.pow(eta, config.getBeta());

            probabilities[i] = tauFactor * etaFactor;
            sum += probabilities[i];
        }

        // Normalizar y seleccionar con ruleta
        if (sum <= 0) {
            return candidates.get(random.nextInt(candidates.size()));
        }

        double r = random.nextDouble() * sum;
        double cumulative = 0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += probabilities[i];
            if (r <= cumulative) {
                return candidates.get(i);
            }
        }

        return candidates.get(candidates.size() - 1);
    }

    /**
     * Calcula la deseabilidad heuristica de un vuelo.
     * Un valor mayor implica una opcion mas atractiva.
     *
     * Considera:
     * 1. Tiempo de viaje y espera
     * 2. Bono por llegar directo al destino
     * 3. Bono por aproximarse al continente del destino
     * 4. Holgura restante frente al plazo
     */
    private double computeHeuristic(Flight f, ShipmentRequest req, long currentTime) {
        long legMinutes = Math.max(1L, f.getArrivalUtcMinutes() - currentTime);
        long waitMinutes = Math.max(0L, f.getDepartureUtcMinutes() - currentTime);
        double eta = 1.0 / (1.0 + legMinutes);

        // Bono fuerte para vuelos que llegan directamente al destino
        if (f.getDestination().equals(req.getDestination())) {
            eta *= 8.0;
        }

        // Bono si el siguiente aeropuerto cae en el mismo continente del destino final
        if (f.getDestination().getContinent() == req.getDestination().getContinent()) {
            eta *= 1.8;
        }

        // Penalizacion si este vuelo consume mas plazo del conveniente
        long elapsedMinutes = f.getArrivalUtcMinutes() - req.getCreationTimeMinutes();
        long slackMinutes = req.getDeadlineMinutes() - elapsedMinutes;
        if (slackMinutes < 0) {
            eta *= 0.15;
        } else {
            eta *= 1.2 + (double) slackMinutes / Math.max(1L, req.getDeadlineMinutes());
        }

        if (waitMinutes > 12L * 60L) {
            eta *= 0.6;
        }

        if (legMinutes > 36L * 60L) {
            eta *= 0.5;
        }

        return Math.max(eta, 1.0 / (10.0 * TimeUtil.MINUTES_PER_DAY));
    }

    private int projectedWarehouseOverflow(String airportCode,
                                           long start,
                                           long end,
                                           int quantity,
                                           Map<String, Map<Long, Integer>> committedStock,
                                           Map<String, Map<Long, Integer>> tentativeStock) {
        if (end <= start) {
            return 0;
        }

        int capacity = network.getAirport(airportCode).getWarehouseCapacity();
        int overflow = 0;
        long firstBucket = bucket(start);
        long lastBucket = bucket(end - 1L);
        Map<Long, Integer> committed = committedStock.getOrDefault(airportCode, Map.of());
        Map<Long, Integer> tentative = tentativeStock.getOrDefault(airportCode, Map.of());
        for (long b = firstBucket; b <= lastBucket; b++) {
            int projected = committed.getOrDefault(b, 0)
                    + tentative.getOrDefault(b, 0)
                    + quantity;
            overflow = Math.max(overflow, projected - capacity);
        }
        return Math.max(0, overflow);
    }

    private void addStockInterval(Map<String, Map<Long, Integer>> stock,
                                  String airportCode,
                                  long start,
                                  long end,
                                  int quantity) {
        if (end <= start) {
            return;
        }
        Map<Long, Integer> buckets = stock.computeIfAbsent(airportCode, key -> new HashMap<>());
        long firstBucket = bucket(start);
        long lastBucket = bucket(end - 1L);
        for (long b = firstBucket; b <= lastBucket; b++) {
            buckets.merge(b, quantity, Integer::sum);
        }
    }

    private void mergeStock(Map<String, Map<Long, Integer>> committedStock,
                            Map<String, Map<Long, Integer>> tentativeStock) {
        for (Map.Entry<String, Map<Long, Integer>> entry : tentativeStock.entrySet()) {
            Map<Long, Integer> committedBuckets =
                    committedStock.computeIfAbsent(entry.getKey(), key -> new HashMap<>());
            for (Map.Entry<Long, Integer> bucket : entry.getValue().entrySet()) {
                committedBuckets.merge(bucket.getKey(), bucket.getValue(), Integer::sum);
            }
        }
    }

    private long bucket(long minute) {
        return Math.floorDiv(minute, STOCK_BUCKET_MINUTES);
    }

    /**
     * Devuelve todos los vuelos usados por la solucion de esta hormiga.
     */
    public List<Flight> getAllUsedFlights() {
        List<Flight> all = new ArrayList<>();
        if (solution != null) {
            for (RouteAssignment ra : solution.getRoutes()) {
                all.addAll(ra.getFlights());
            }
        }
        return all;
    }

    public Solution getSolution() { return solution; }
}
