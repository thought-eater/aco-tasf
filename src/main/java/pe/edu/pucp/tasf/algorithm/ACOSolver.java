package pe.edu.pucp.tasf.algorithm;

import pe.edu.pucp.tasf.model.Flight;
import pe.edu.pucp.tasf.model.LogisticsNetwork;
import pe.edu.pucp.tasf.model.RouteAssignment;
import pe.edu.pucp.tasf.model.ShipmentRequest;
import pe.edu.pucp.tasf.model.Solution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

// SLF4J: descomentar las siguientes lineas para usar logging profesional
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

/**
 * Solver de Ant Colony Optimization (ACO) para el problema de ruteo de maletas
 * de Tasf.B2B.
 *
 * Implementa una variante MAX-MIN Ant System (MMAS) con:
 *   - multiples hormigas construyendo soluciones de forma probabilistica
 *   - evaporacion de feromona para olvidar soluciones pobres
 *   - deposito de feromona proporcional a la calidad de la solucion
 *   - refuerzo elitista para la mejor solucion conocida
 *   - cotas MMAS para evitar convergencia prematura
 *
 * Flujo del algoritmo por iteracion:
 *   1. Cada hormiga construye una solucion completa
 *   2. Se evalua cada solucion
 *   3. Se evapora la feromona en todas las aristas
 *   4. Se deposita feromona en las aristas usadas por buenas soluciones
 *   5. Las hormigas elitistas refuerzan la mejor solucion global
 *   6. Se actualiza la mejor solucion si hubo mejora
 */
public class ACOSolver {

    // SLF4J: descomentar la siguiente linea y reemplazar System.out por logger.info(...)
    // private static final Logger logger = LoggerFactory.getLogger(ACOSolver.class);

    private final LogisticsNetwork network;
    private final List<ShipmentRequest> requests;
    private final ACOConfig config;
    private final Random random;

    private PheromoneMatrix pheromones;
    private Solution bestSolution;
    private double bestFitness;
    private int iteration;
    private int iterationsRun;

    // Estadisticas
    private final List<Double> fitnessHistory;
    private final List<Double> avgFitnessHistory;
    private int improvementCount;
    private long startTimeMs;

    public ACOSolver(LogisticsNetwork network, List<ShipmentRequest> requests,
                     ACOConfig config) {
        this.network = network;
        this.requests = requests;
        this.config = config;
        this.random = new Random(config.getSeed());
        this.fitnessHistory = new ArrayList<>();
        this.avgFitnessHistory = new ArrayList<>();
        this.improvementCount = 0;
    }

    /**
     * Ejecuta el algoritmo ACO y devuelve la mejor solucion encontrada.
     */
    public Solution solve() {
        startTimeMs = System.currentTimeMillis();
        fitnessHistory.clear();
        avgFitnessHistory.clear();
        improvementCount = 0;
        iterationsRun = 0;
        System.out.println("=== ACO (Ant Colony Optimization) started ===");
        System.out.printf("Planning requests: %d, Airports: %d, Flight schedules: %d, Ants: %d%n",
                requests.size(), network.getAirportCount(), network.getFlightCount(),
                config.getAntCount());
        System.out.printf("Parameters: alpha=%.1f, beta=%.1f, rho=%.2f, Q=%.1f%n",
                config.getAlpha(), config.getBeta(), config.getRho(), config.getQ());

        // Inicializar la matriz de feromonas
        pheromones = new PheromoneMatrix(network, config);

        bestSolution = null;
        bestFitness = Double.MAX_VALUE;

        // Bucle principal de ACO
        for (iteration = 1; iteration <= config.getMaxIterations(); iteration++) {

            if (isTimeLimitReached()) {
                System.out.println("Time limit reached at iteration " + iteration);
                break;
            }

            // Paso 1: cada hormiga construye una solucion
            List<Ant> ants = new ArrayList<>();
            Solution iterBest = null;
            double iterBestFitness = Double.MAX_VALUE;
            double fitnessSum = 0;

            for (int a = 0; a < config.getAntCount(); a++) {
                Ant ant = new Ant(network, pheromones, config, random);
                Solution sol = ant.constructSolution(requests);
                ants.add(ant);

                double fitness = sol.getFitness();
                fitnessSum += fitness;

                // Registrar la mejor solucion de la iteracion
                if (fitness < iterBestFitness) {
                    iterBestFitness = fitness;
                    iterBest = sol;
                }

                // Registrar la mejor solucion global
                if (fitness < bestFitness) {
                    bestFitness = fitness;
                    bestSolution = sol.copy();
                    improvementCount++;
                    System.out.printf("Iter %d, Ant %d: NEW BEST fitness=%.2f, late=%d%n",
                            iteration, a + 1, bestFitness, bestSolution.getLateCount());
                }
            }

            // Paso 2: evaporacion de feromona
            pheromones.evaporate();

            // Paso 3: deposito de feromona de la mejor hormiga de la iteracion
            if (iterBest != null) {
                // Encontrar la hormiga cuya solucion fue la mejor de la iteracion
                for (Ant ant : ants) {
                    if (ant.getSolution() == iterBest) {
                        pheromones.deposit(ant.getAllUsedFlights(), iterBestFitness);
                        break;
                    }
                }
            }

            // Paso 4: refuerzo elitista sobre la mejor solucion global
            if (bestSolution != null) {
                List<Flight> bestFlights = new ArrayList<>();
                for (RouteAssignment ra : bestSolution.getRoutes()) {
                    bestFlights.addAll(ra.getFlights());
                }
                pheromones.depositElitist(bestFlights, bestFitness, config.getElitistAnts());
            }

            // Registrar estadisticas
            fitnessHistory.add(bestFitness);
            avgFitnessHistory.add(fitnessSum / config.getAntCount());
            iterationsRun = iteration;

            if (bestFitness <= 0.0) {
                System.out.println("Optimal solution reached at iteration " + iteration);
                break;
            }

            // Log periodico
            if (iteration % 50 == 0) {
                double avgFitness = fitnessSum / config.getAntCount();
                System.out.printf("Iter %d: best=%.2f, iter_best=%.2f, avg=%.2f | %s%n",
                        iteration, bestFitness, iterBestFitness, avgFitness,
                        pheromones.getStats());
            }
        }

        if (bestSolution != null
                && Boolean.parseBoolean(System.getProperty("tasf.repairWarehouse", "true"))) {
            WarehouseRepair repair = new WarehouseRepair(network, config);
            Solution repaired = repair.repair(bestSolution);
            if (repaired != null && repaired.getFitness() < bestFitness) {
                bestSolution = repaired.copy();
                bestFitness = bestSolution.getFitness();
                improvementCount++;
                fitnessHistory.add(bestFitness);
            }
        }

        long elapsed = System.currentTimeMillis() - startTimeMs;
        System.out.println("=== ACO finished ===");
        System.out.printf("Iterations: %d, Improvements: %d, Time: %d ms%n",
                getIterationsRun(), improvementCount, elapsed);
        if (bestSolution != null) {
            System.out.println("Best solution: " + bestSolution);
            System.out.println("Semaphore status: " + getSemaphoreStatus(bestSolution));
        } else {
            System.out.println("No feasible solution was produced.");
        }

        return bestSolution;
    }

    // ========================= REPLANIFICACION DINAMICA =========================

    /**
     * Replanifica la solucion despues de cancelar un vuelo.
     * Reinicia el estado afectado y ejecuta un ciclo corto de ACO.
     */
    public Solution replanify(Solution currentSol, String cancelledFlightId) {
        System.out.println("ACO Replanification triggered for cancelled flight: " + cancelledFlightId);
        startTimeMs = System.currentTimeMillis();
        fitnessHistory.clear();
        avgFitnessHistory.clear();
        improvementCount = 0;
        iterationsRun = 0;
        network.cancelFlight(cancelledFlightId);

        // Contar rutas afectadas
        int affected = 0;
        for (RouteAssignment ra : currentSol.getRoutes()) {
            for (Flight f : ra.getFlights()) {
                if (f.getScheduleId().equals(cancelledFlightId)) {
                    affected++;
                    break;
                }
            }
        }
        System.out.println("Affected shipments: " + affected);

        // Luego se ejecuta un ciclo corto de ACO sobre la red ya modificada
        this.bestSolution = null;
        this.bestFitness = Double.MAX_VALUE;

        int replanIter = Math.min(50, config.getMaxIterations());

        for (iteration = 1; iteration <= replanIter; iteration++) {
            double fitnessSum = 0.0;
            for (int a = 0; a < config.getAntCount(); a++) {
                Ant ant = new Ant(network, pheromones, config, random);
                Solution sol = ant.constructSolution(requests);

                double fitness = sol.getFitness();
                fitnessSum += fitness;
                if (fitness < bestFitness) {
                    bestFitness = fitness;
                    bestSolution = sol.copy();
                    improvementCount++;
                }
            }

            pheromones.evaporate();

            if (bestSolution != null) {
                List<Flight> bestFlights = new ArrayList<>();
                for (RouteAssignment ra : bestSolution.getRoutes()) {
                    bestFlights.addAll(ra.getFlights());
                }
                pheromones.deposit(bestFlights, bestFitness);
            }

            if (bestSolution != null) {
                fitnessHistory.add(bestFitness);
                avgFitnessHistory.add(fitnessSum / config.getAntCount());
            }
            iterationsRun = iteration;
        }

        if (bestSolution != null) {
            System.out.println("ACO Replanification done. New solution: " + bestSolution);
        } else {
            System.out.println("ACO Replanification ended without an alternative solution.");
        }
        return bestSolution;
    }

    // ========================= ESTADO DEL SEMAFORO =========================

    public String getSemaphoreStatus(Solution solution) {
        if (solution == null || solution.getTotalSuitcases() == 0) return "GREEN";
        double lateRatio = (double) solution.getLateCount() / solution.getTotalSuitcases();
        if (lateRatio <= config.getGreenThreshold()) return "GREEN";
        if (lateRatio <= config.getAmberThreshold()) return "AMBER";
        return "RED";
    }

    // ========================= REPORTE =========================

    public List<Double> getFitnessHistory() {
        return Collections.unmodifiableList(fitnessHistory);
    }

    public List<Double> getAvgFitnessHistory() {
        return Collections.unmodifiableList(avgFitnessHistory);
    }

    public int getImprovementCount() { return improvementCount; }
    public int getIterationsRun() { return iterationsRun; }

    private boolean isTimeLimitReached() {
        return (System.currentTimeMillis() - startTimeMs) >= config.getTimeLimitMs();
    }

    /**
     * Imprime un reporte detallado de la mejor solucion encontrada.
     */
    public void printReport() {
        Solution sol = bestSolution;
        if (sol == null) {
            System.out.println("\n========== ACO REPORT ==========");
            System.out.println("No solution available.");
            System.out.println("========================================\n");
            return;
        }
        System.out.println("\n========== ACO REPORT ==========");
        System.out.println("Algorithm:         Ant Colony Optimization (MMAS)");
        System.out.printf("Parameters:        alpha=%.1f, beta=%.1f, rho=%.2f, Q=%.1f, ants=%d%n",
                config.getAlpha(), config.getBeta(), config.getRho(),
                config.getQ(), config.getAntCount());
        System.out.println("Iterations run:    " + getIterationsRun());
        System.out.println("Improvements:      " + improvementCount);
        System.out.println("Total requests:    " + sol.size());
        System.out.println("Total suitcases:   " + sol.getTotalSuitcases());
        System.out.println("Delivered:         " + sol.getDeliveredCount());
        System.out.println("Undelivered:       " + sol.getUndeliveredCount());
        System.out.println("Late suitcases:    " + sol.getLateCount());
        System.out.printf("Total delay:       %.2f days%n", sol.getTotalDelay());
        System.out.println("Capacity overflow: " + sol.getCapacityOverflow());
        System.out.println("Warehouse overflow:" + sol.getWarehouseOverflow());
        System.out.printf("Fitness:           %.2f%n", sol.getFitness());
        System.out.println("Semaphore:         " + getSemaphoreStatus(sol));
        System.out.println();

        int maxRoutesToShow = Math.min(20, sol.size());
        System.out.println("--- Route details (first " + maxRoutesToShow + ") ---");
        for (int i = 0; i < maxRoutesToShow; i++) {
            RouteAssignment ra = sol.getRoute(i);
            System.out.println("  " + ra);
        }
        if (sol.size() > maxRoutesToShow) {
            System.out.println("  ... " + (sol.size() - maxRoutesToShow) + " routes more");
        }

        // Informacion de convergencia
        if (!fitnessHistory.isEmpty() && !avgFitnessHistory.isEmpty()) {
            System.out.println();
            System.out.println("--- Convergence ---");
            System.out.printf("  Initial best:   %.2f%n", fitnessHistory.get(0));
            System.out.printf("  Final best:     %.2f%n", fitnessHistory.get(fitnessHistory.size() - 1));
            System.out.printf("  Initial avg:    %.2f%n", avgFitnessHistory.get(0));
            System.out.printf("  Final avg:      %.2f%n", avgFitnessHistory.get(avgFitnessHistory.size() - 1));
        }
        System.out.println("========================================\n");
    }
}
