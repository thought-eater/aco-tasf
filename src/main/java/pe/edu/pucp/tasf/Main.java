package pe.edu.pucp.tasf;

import pe.edu.pucp.tasf.algorithm.ACOConfig;
import pe.edu.pucp.tasf.algorithm.ACOSolver;
import pe.edu.pucp.tasf.model.Flight;
import pe.edu.pucp.tasf.model.LogisticsNetwork;
import pe.edu.pucp.tasf.model.RouteAssignment;
import pe.edu.pucp.tasf.model.ShipmentRequest;
import pe.edu.pucp.tasf.model.Solution;
import pe.edu.pucp.tasf.util.ProblemDataLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Punto de entrada del planificador ACO de Tasf.B2B usando los datos reales:
 * catalogo de aeropuertos, planes de vuelo y ZIP de envios.
 */
public class Main {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int AGGREGATION_THRESHOLD =
            Integer.getInteger("tasf.aggregateThreshold", 2_000);
    private static final int AGGREGATION_BATCH_SIZE =
            Integer.getInteger("tasf.aggregateBatchSize", 25);

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0].toUpperCase() : "E1";

        Path airportsPath = resolveDataPath("tasf.airports", "aeropuertos.txt");
        Path flightsPath = resolveDataPath("tasf.flights", "planes_vuelo.txt");
        Path shipmentsPath = resolveDataPath("tasf.shipments", "_envios_preliminar_.zip");

        ensureExists(airportsPath);
        ensureExists(flightsPath);
        ensureExists(shipmentsPath);

        ProblemDataLoader loader = new ProblemDataLoader();
        LocalDate earliestDate = loader.findEarliestShipmentDate(shipmentsPath);

        printBanner(airportsPath, flightsPath, shipmentsPath, earliestDate);

        switch (scenario) {
            case "E1" -> {
                LocalDate startDate = args.length > 1
                        ? LocalDate.parse(args[1], DATE_FORMAT)
                        : earliestDate;
                int cancellations = args.length > 2 ? Integer.parseInt(args[2]) : 0;
                runRealTimeSimulation(loader, airportsPath, flightsPath, shipmentsPath, startDate, cancellations);
            }
            case "E2" -> {
                int days = args.length > 1 ? Integer.parseInt(args[1]) : 5;
                LocalDate startDate = args.length > 2
                        ? LocalDate.parse(args[2], DATE_FORMAT)
                        : earliestDate;
                int cancellations = args.length > 3 ? Integer.parseInt(args[3]) : 0;
                runPeriodSimulation(loader, airportsPath, flightsPath, shipmentsPath, startDate, days, cancellations);
            }
            case "E3" -> {
                LocalDate startDate = args.length > 1
                        ? LocalDate.parse(args[1], DATE_FORMAT)
                        : earliestDate;
                int maxDays = args.length > 2 ? Integer.parseInt(args[2]) : 30;
                int cancellations = args.length > 3 ? Integer.parseInt(args[3]) : 0;
                runCollapseSimulation(loader, airportsPath, flightsPath, shipmentsPath, startDate, maxDays, cancellations);
            }
            default -> printUsage();
        }
    }

    /**
     * E2: simulacion de periodo completo (3/5/7 dias u otro horizonte).
     */
    private static void runPeriodSimulation(ProblemDataLoader loader,
                                            Path airportsPath,
                                            Path flightsPath,
                                            Path shipmentsPath,
                                            LocalDate startDate,
                                            int days,
                                            int cancellations) throws IOException {
        System.out.println("=== SCENARIO E2: Period Simulation ===");
        System.out.printf("Requested horizon: %s to %s (%d days)%n%n",
                startDate, startDate.plusDays(days - 1L), days);
        System.out.printf("Requested cancellations: %d%n%n", cancellations);

        LogisticsNetwork network = loader.loadNetwork(airportsPath, flightsPath);
        Map<LocalDate, List<ShipmentRequest>> requestsByDate =
                loader.loadShipmentsByDate(shipmentsPath, network, startDate, days);

        List<ShipmentRequest> rawRequests = flattenRequests(requestsByDate);
        if (rawRequests.isEmpty()) {
            System.out.println("No shipment requests were found for the selected period.");
            return;
        }

        List<ShipmentRequest> planningRequests = maybeAggregateRequests(loader, rawRequests);
        printDemandSummary(requestsByDate, rawRequests, planningRequests);

        ACOConfig config = buildPeriodConfig(planningRequests.size(), days);
        ACOSolver solver = new ACOSolver(network, planningRequests, config);
        Solution solution = solver.solve();
        solver.printReport();
        applyCancellationsAndReplanify(solver, solution, cancellations);
    }

    /**
     * E1: operaciones dia a dia. Planifica un dia y, si se solicita por parametro,
     * dispara replanificaciones por cancelaciones.
     */
    private static void runRealTimeSimulation(ProblemDataLoader loader,
                                              Path airportsPath,
                                              Path flightsPath,
                                              Path shipmentsPath,
                                              LocalDate date,
                                              int cancellations) throws IOException {
        System.out.println("=== SCENARIO E1: Real-Time Daily Operations ===");
        System.out.printf("Selected date: %s%n%n", date);
        System.out.printf("Requested cancellations: %d%n%n", cancellations);

        LogisticsNetwork network = loader.loadNetwork(airportsPath, flightsPath);
        Map<LocalDate, List<ShipmentRequest>> requestsByDate =
                loader.loadShipmentsByDate(shipmentsPath, network, date, 1);

        List<ShipmentRequest> rawRequests = flattenRequests(requestsByDate);
        if (rawRequests.isEmpty()) {
            System.out.println("No shipment requests were found for the selected date.");
            return;
        }

        List<ShipmentRequest> planningRequests = maybeAggregateRequests(loader, rawRequests);
        printDemandSummary(requestsByDate, rawRequests, planningRequests);

        ACOConfig config = buildRealTimeConfig(planningRequests.size());
        ACOSolver solver = new ACOSolver(network, planningRequests, config);
        Solution solution = solver.solve();
        solver.printReport();
        applyCancellationsAndReplanify(solver, solution, cancellations);
    }

    /**
     * E3: resuelve cada dia de forma independiente siguiendo la demanda
     * historica real hasta que el semaforo llegue a RED o termine el horizonte.
     */
    private static void runCollapseSimulation(ProblemDataLoader loader,
                                              Path airportsPath,
                                              Path flightsPath,
                                              Path shipmentsPath,
                                              LocalDate startDate,
                                              int maxDays,
                                              int cancellations) throws IOException {
        System.out.println("=== SCENARIO E3: Simulation Until Collapse ===");
        System.out.printf("Initial date: %s | Max days: %d%n%n", startDate, maxDays);
        System.out.printf("Requested cancellations per simulated day: %d%n%n", cancellations);

        LogisticsNetwork network = loader.loadNetwork(airportsPath, flightsPath);
        Map<LocalDate, List<ShipmentRequest>> requestsByDate =
                loader.loadShipmentsByDate(shipmentsPath, network, startDate, maxDays);

        if (requestsByDate.isEmpty()) {
            System.out.println("No shipment requests were found for the selected horizon.");
            return;
        }

        String status = "GREEN";
        int simulatedDays = 0;

        for (Map.Entry<LocalDate, List<ShipmentRequest>> entry : requestsByDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<ShipmentRequest> rawRequests = entry.getValue();
            List<ShipmentRequest> planningRequests = maybeAggregateRequests(loader, rawRequests);
            ACOConfig config = buildCollapseConfig(planningRequests.size());

            simulatedDays++;
            System.out.println("--- Collapse day " + simulatedDays + " | " + date + " ---");
            System.out.printf("Raw requests: %d | Planning requests: %d | Suitcases: %d%n",
                    rawRequests.size(),
                    planningRequests.size(),
                    rawRequests.stream().mapToInt(ShipmentRequest::getQuantity).sum());

            ACOSolver solver = new ACOSolver(network, planningRequests, config);
            Solution solution = solver.solve();
            solution = applyCancellationsAndReplanify(solver, solution, cancellations);
            status = solver.getSemaphoreStatus(solution);

            if (solution == null) {
                System.out.printf("Delivered=0 | Late=0 (0.0%%) | Undelivered=%d | Semaphore=%s%n%n",
                        planningRequests.stream().mapToInt(ShipmentRequest::getQuantity).sum(),
                        formatSemaphore(status));
                continue;
            }

            double latePercent = solution.getTotalSuitcases() > 0
                    ? 100.0 * solution.getLateCount() / solution.getTotalSuitcases()
                    : 0.0;

            System.out.printf("Delivered=%d | Late=%d (%.1f%%) | Undelivered=%d | Semaphore=%s%n%n",
                    solution.getDeliveredCount(),
                    solution.getLateCount(),
                    latePercent,
                    solution.getUndeliveredCount(),
                    formatSemaphore(status));

            if ("RED".equals(status)) {
                System.out.println("*** SYSTEM COLLAPSE detected on " + date + " ***");
                return;
            }
        }

        System.out.println("Simulation finished without reaching RED semaphore.");
    }

    private static Solution applyCancellationsAndReplanify(ACOSolver solver,
                                                           Solution initialSolution,
                                                           int cancellationCount) {
        Solution currentSolution = initialSolution;
        if (cancellationCount <= 0) {
            return currentSolution;
        }

        for (int i = 1; i <= cancellationCount; i++) {
            String cancelledFlightId = pickMostUsedFlightSchedule(currentSolution);
            if (cancelledFlightId == null) {
                System.out.println("No flight schedule was used in the solution, replanification skipped.");
                return currentSolution;
            }

            System.out.println("\n--- Dynamic Event " + i + " of " + cancellationCount + " ---");
            System.out.println("Cancelling schedule: " + cancelledFlightId);
            long replanStart = System.currentTimeMillis();
            Solution replannedSolution = solver.replanify(currentSolution, cancelledFlightId);
            long replanTime = System.currentTimeMillis() - replanStart;

            System.out.printf("Replanification time: %d ms%n", replanTime);
            solver.printReport();

            if (replannedSolution == null) {
                return currentSolution;
            }
            currentSolution = replannedSolution;
        }

        return currentSolution;
    }

    private static List<ShipmentRequest> maybeAggregateRequests(ProblemDataLoader loader,
                                                                List<ShipmentRequest> rawRequests) {
        if (rawRequests.size() <= AGGREGATION_THRESHOLD) {
            return rawRequests;
        }

        List<ShipmentRequest> aggregated = loader.aggregateRequests(rawRequests, AGGREGATION_BATCH_SIZE);
        System.out.printf("Aggregation enabled: raw=%d -> planning=%d (batch size %d)%n",
                rawRequests.size(), aggregated.size(), AGGREGATION_BATCH_SIZE);
        return aggregated;
    }

    private static void printDemandSummary(Map<LocalDate, List<ShipmentRequest>> requestsByDate,
                                           List<ShipmentRequest> rawRequests,
                                           List<ShipmentRequest> planningRequests) {
        int rawSuitcases = rawRequests.stream().mapToInt(ShipmentRequest::getQuantity).sum();
        int planningSuitcases = planningRequests.stream().mapToInt(ShipmentRequest::getQuantity).sum();

        System.out.println("Loaded demand:");
        System.out.printf("  Days loaded: %d%n", requestsByDate.size());
        System.out.printf("  Raw requests: %d | Suitcases: %d%n", rawRequests.size(), rawSuitcases);
        System.out.printf("  Planning requests: %d | Suitcases: %d%n", planningRequests.size(), planningSuitcases);

        requestsByDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int dailySuitcases = entry.getValue().stream()
                            .mapToInt(ShipmentRequest::getQuantity)
                            .sum();
                    System.out.printf("  %s -> requests=%d, suitcases=%d%n",
                            entry.getKey(),
                            entry.getValue().size(),
                            dailySuitcases);
                });
        System.out.println();
    }

    private static List<ShipmentRequest> flattenRequests(Map<LocalDate, List<ShipmentRequest>> requestsByDate) {
        List<ShipmentRequest> flattened = new ArrayList<>();
        requestsByDate.values().forEach(flattened::addAll);
        flattened.sort(Comparator
                .comparingLong(ShipmentRequest::getCreationTimeMinutes)
                .thenComparing(ShipmentRequest::getId));
        return flattened;
    }

    private static String pickMostUsedFlightSchedule(Solution solution) {
        if (solution == null) {
            return null;
        }

        Map<String, Integer> usage = new HashMap<>();
        for (RouteAssignment route : solution.getRoutes()) {
            for (Flight flight : route.getFlights()) {
                usage.merge(flight.getScheduleId(), 1, Integer::sum);
            }
        }

        return usage.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static ACOConfig buildPeriodConfig(int requestCount, int days) {
        int ants = requestCount <= 300 ? 20 : requestCount <= 1_500 ? 14 : 10;
        int iterations = requestCount <= 300 ? 180 : requestCount <= 1_500 ? 120 : 80;
        long timeLimitMs = Long.getLong("tasf.timeLimitMs", Math.max(90_000L, days * 90_000L));

        return new ACOConfig()
                .maxIterations(Integer.getInteger("tasf.iterations", iterations))
                .antCount(Integer.getInteger("tasf.ants", ants))
                .alpha(Double.parseDouble(System.getProperty("tasf.alpha", "1.0")))
                .beta(Double.parseDouble(System.getProperty("tasf.beta", "3.0")))
                .rho(Double.parseDouble(System.getProperty("tasf.rho", "0.12")))
                .Q(Double.parseDouble(System.getProperty("tasf.Q", "150.0")))
                .elitistAnts(Integer.getInteger("tasf.elitistAnts", 3))
                .maxHops(Integer.getInteger("tasf.maxHops", 4))
                .timeLimitMs(timeLimitMs)
                .seed(Long.getLong("tasf.seed", 42L));
    }

    private static ACOConfig buildRealTimeConfig(int requestCount) {
        int ants = requestCount <= 300 ? 12 : 8;
        int iterations = requestCount <= 300 ? 80 : 50;
        long timeLimitMs = Long.getLong("tasf.timeLimitMs", 20_000L);

        return new ACOConfig()
                .maxIterations(Integer.getInteger("tasf.iterations", iterations))
                .antCount(Integer.getInteger("tasf.ants", ants))
                .alpha(Double.parseDouble(System.getProperty("tasf.alpha", "1.0")))
                .beta(Double.parseDouble(System.getProperty("tasf.beta", "3.2")))
                .rho(Double.parseDouble(System.getProperty("tasf.rho", "0.15")))
                .Q(Double.parseDouble(System.getProperty("tasf.Q", "120.0")))
                .elitistAnts(Integer.getInteger("tasf.elitistAnts", 2))
                .maxHops(Integer.getInteger("tasf.maxHops", 4))
                .timeLimitMs(timeLimitMs)
                .seed(Long.getLong("tasf.seed", 42L));
    }

    private static ACOConfig buildCollapseConfig(int requestCount) {
        int ants = requestCount <= 300 ? 16 : 10;
        int iterations = requestCount <= 300 ? 100 : 60;
        long timeLimitMs = Long.getLong("tasf.timeLimitMs", 60_000L);

        return new ACOConfig()
                .maxIterations(Integer.getInteger("tasf.iterations", iterations))
                .antCount(Integer.getInteger("tasf.ants", ants))
                .alpha(Double.parseDouble(System.getProperty("tasf.alpha", "1.0")))
                .beta(Double.parseDouble(System.getProperty("tasf.beta", "3.0")))
                .rho(Double.parseDouble(System.getProperty("tasf.rho", "0.12")))
                .Q(Double.parseDouble(System.getProperty("tasf.Q", "150.0")))
                .elitistAnts(Integer.getInteger("tasf.elitistAnts", 2))
                .maxHops(Integer.getInteger("tasf.maxHops", 4))
                .timeLimitMs(timeLimitMs)
                .seed(Long.getLong("tasf.seed", 42L));
    }

    private static void printBanner(Path airportsPath, Path flightsPath,
                                    Path shipmentsPath, LocalDate earliestDate) {
        System.out.println("==========================================");
        System.out.println("Tasf.B2B - Ant Colony Optimization");
        System.out.println("Real input mode");
        System.out.println("==========================================");
        System.out.println("Airports : " + airportsPath);
        System.out.println("Flights  : " + flightsPath);
        System.out.println("Shipments: " + shipmentsPath);
        System.out.println("Earliest shipment date found: " + earliestDate);
        System.out.println();
    }

    private static void printUsage() {
        System.out.println("Uso:");
        System.out.println("  java -jar aco-tasf.jar E1 [date] [cancellations]");
        System.out.println("  java -jar aco-tasf.jar E2 [days] [startDate] [cancellations]");
        System.out.println("  java -jar aco-tasf.jar E3 [startDate] [maxDays] [cancellations]");
        System.out.println();
        System.out.println("Formato de fecha: yyyymmdd");
        System.out.println("Ejemplos:");
        System.out.println("  java -jar aco-tasf.jar E1 20260102 2");
        System.out.println("  java -jar aco-tasf.jar E2 5 20260102 2");
        System.out.println("  java -jar aco-tasf.jar E3 20260102 30 2");
    }

    private static Path resolveDataPath(String propertyName, String fileName) {
        String override = System.getProperty(propertyName);
        if (override != null && !override.isBlank()) {
            Path overridePath = Path.of(override);
            if (!overridePath.isAbsolute()) {
                overridePath = Path.of("").toAbsolutePath().resolve(overridePath);
            }
            return overridePath.normalize();
        }

        Path cwd = Path.of("").toAbsolutePath();
        Path internalData = cwd.resolve("data").resolve(fileName);
        if (Files.exists(internalData)) {
            return internalData.toAbsolutePath().normalize();
        }

        Path internalFile = cwd.resolve(fileName);
        if (Files.exists(internalFile)) {
            return internalFile.toAbsolutePath().normalize();
        }

        Path parent = cwd.getParent();
        if (parent != null) {
            Path sibling = parent.resolve(fileName);
            if (Files.exists(sibling)) {
                return sibling.toAbsolutePath().normalize();
            }
        }

        return internalData.toAbsolutePath().normalize();
    }

    private static void ensureExists(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Required input file not found: " + path);
        }
    }

    private static String formatSemaphore(String status) {
        return switch (status) {
            case "GREEN" -> "GREEN";
            case "AMBER" -> "AMBER";
            case "RED" -> "RED";
            default -> status;
        };
    }
}
