package pe.edu.pucp.tasf.algorithm;

/**
 * Parametros de configuracion para el algoritmo Ant Colony Optimization.
 * Todos los parametros se pueden ajustar para experimentacion numerica.
 *
 * Parametros principales de ACO:
 *   alpha  - peso de la influencia de feromonas
 *   beta   - peso de la informacion heuristica
 *   rho    - tasa de evaporacion
 *   Q      - constante de deposito de feromonas
 */
public class ACOConfig {
    private int maxIterations = 500;
    private int antCount = 30;                 // numero de hormigas por iteracion
    private double alpha = 1.0;                // influencia de feromona
    private double beta = 2.0;                 // influencia heuristica
    private double rho = 0.1;                  // tasa de evaporacion (0 = sin evaporacion, 1 = total)
    private double Q = 100.0;                  // constante de deposito de feromona
    private double tauMin = 0.1;               // feromona minima (MMAS)
    private double tauMax = 10.0;              // feromona maxima (MMAS)
    private double tauInitial = 1.0;           // feromona inicial en todas las aristas
    private int maxHops = 3;                   // maximo de saltos por ruta
    private long timeLimitMs = 90 * 60 * 1000; // limite maximo de 90 minutos para E1
    private long seed = 42;                    // semilla para reproducibilidad
    private int elitistAnts = 3;               // hormigas elite que refuerzan la mejor solucion

    // Pesos de penalizacion para el fitness
    private double penaltyLate = 1000.0;
    private double penaltyCapacity = 5000.0;
    private double penaltyDelay = 100.0;

    // Umbrales del semaforo (verde/ambar/rojo)
    private double greenThreshold = 0.1;
    private double amberThreshold = 0.3;

    // Patron builder
    public ACOConfig maxIterations(int val) { this.maxIterations = val; return this; }
    public ACOConfig antCount(int val) { this.antCount = val; return this; }
    public ACOConfig alpha(double val) { this.alpha = val; return this; }
    public ACOConfig beta(double val) { this.beta = val; return this; }
    public ACOConfig rho(double val) { this.rho = val; return this; }
    public ACOConfig Q(double val) { this.Q = val; return this; }
    public ACOConfig tauMin(double val) { this.tauMin = val; return this; }
    public ACOConfig tauMax(double val) { this.tauMax = val; return this; }
    public ACOConfig tauInitial(double val) { this.tauInitial = val; return this; }
    public ACOConfig maxHops(int val) { this.maxHops = val; return this; }
    public ACOConfig timeLimitMs(long val) { this.timeLimitMs = val; return this; }
    public ACOConfig seed(long val) { this.seed = val; return this; }
    public ACOConfig elitistAnts(int val) { this.elitistAnts = val; return this; }
    public ACOConfig penaltyLate(double val) { this.penaltyLate = val; return this; }
    public ACOConfig penaltyCapacity(double val) { this.penaltyCapacity = val; return this; }
    public ACOConfig greenThreshold(double val) { this.greenThreshold = val; return this; }
    public ACOConfig amberThreshold(double val) { this.amberThreshold = val; return this; }

    // Accesores
    public int getMaxIterations() { return maxIterations; }
    public int getAntCount() { return antCount; }
    public double getAlpha() { return alpha; }
    public double getBeta() { return beta; }
    public double getRho() { return rho; }
    public double getQ() { return Q; }
    public double getTauMin() { return tauMin; }
    public double getTauMax() { return tauMax; }
    public double getTauInitial() { return tauInitial; }
    public int getMaxHops() { return maxHops; }
    public long getTimeLimitMs() { return timeLimitMs; }
    public long getSeed() { return seed; }
    public int getElitistAnts() { return elitistAnts; }
    public double getPenaltyLate() { return penaltyLate; }
    public double getPenaltyCapacity() { return penaltyCapacity; }
    public double getPenaltyDelay() { return penaltyDelay; }
    public double getGreenThreshold() { return greenThreshold; }
    public double getAmberThreshold() { return amberThreshold; }
}
