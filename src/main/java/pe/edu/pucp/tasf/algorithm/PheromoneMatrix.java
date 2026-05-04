package pe.edu.pucp.tasf.algorithm;

import pe.edu.pucp.tasf.model.Flight;
import pe.edu.pucp.tasf.model.LogisticsNetwork;

import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Administra la matriz de feromonas para Ant Colony Optimization.
 *
 * La feromona se deposita sobre vuelos y no sobre pares de aeropuertos,
 * porque puede haber varios vuelos entre el mismo par en horarios distintos.
 * Usa la variante MAX-MIN Ant System (MMAS) con limites de feromona
 * para prevenir convergencia prematura.
 */
public class PheromoneMatrix {

    private final Map<String, Double> pheromones;  // flightId -> nivel de feromona
    private final ACOConfig config;

    public PheromoneMatrix(LogisticsNetwork network, ACOConfig config) {
        this.config = config;
        this.pheromones = new HashMap<>();

        // Inicializar todos los vuelos con tauInitial
        for (Flight f : network.getFlights()) {
            pheromones.put(f.getId(), config.getTauInitial());
        }
    }

    /**
     * Obtiene el nivel de feromona asociado a un vuelo.
     */
    public double getPheromone(String flightId) {
        return pheromones.getOrDefault(flightId, config.getTauInitial());
    }

    /**
     * Obtiene el nivel de feromona para un vuelo a partir de su horario base,
     * incluso si se trata de una salida concreta instanciada.
     */
    public double getPheromoneForFlight(Flight flight) {
        return pheromones.getOrDefault(flight.getScheduleId(), config.getTauInitial());
    }

    /**
     * Evapora feromona en todos los vuelos.
     * tau(i,j) = (1 - rho) * tau(i,j)
     */
    public void evaporate() {
        double factor = 1.0 - config.getRho();
        for (Map.Entry<String, Double> entry : pheromones.entrySet()) {
            double newTau = Math.max(config.getTauMin(), entry.getValue() * factor);
            entry.setValue(newTau);
        }
    }

    /**
     * Deposita feromona sobre una lista de vuelos usados por una hormiga.
     * delta_tau = Q / fitness  (mejor fitness = mas feromona)
     */
    public void deposit(List<Flight> flights, double fitness) {
        if (fitness <= 0) return;
        double deltaTau = config.getQ() / fitness;

        for (Flight f : flights) {
            String scheduleId = f.getScheduleId();
            double current = pheromones.getOrDefault(scheduleId, config.getTauInitial());
            double newTau = Math.min(config.getTauMax(), current + deltaTau);
            pheromones.put(scheduleId, newTau);
        }
    }

    /**
     * Deposita feromona extra para el refuerzo elitista de la mejor solucion global.
     */
    public void depositElitist(List<Flight> flights, double fitness, int elitistWeight) {
        if (fitness <= 0) return;
        double deltaTau = (config.getQ() * elitistWeight) / fitness;

        for (Flight f : flights) {
            String scheduleId = f.getScheduleId();
            double current = pheromones.getOrDefault(scheduleId, config.getTauInitial());
            double newTau = Math.min(config.getTauMax(), current + deltaTau);
            pheromones.put(scheduleId, newTau);
        }
    }

    /**
     * Reinicia todas las feromonas al valor inicial.
     */
    public void reset() {
        for (String key : pheromones.keySet()) {
            pheromones.put(key, config.getTauInitial());
        }
    }

    /**
     * Devuelve estadisticas de la distribucion de feromonas.
     */
    public String getStats() {
        DoubleSummaryStatistics stats = pheromones.values().stream()
                .mapToDouble(Double::doubleValue).summaryStatistics();
        return String.format("Pheromones [min=%.3f, max=%.3f, avg=%.3f, count=%d]",
                stats.getMin(), stats.getMax(), stats.getAverage(), stats.getCount());
    }
}
