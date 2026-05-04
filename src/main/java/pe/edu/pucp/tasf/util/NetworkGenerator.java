package pe.edu.pucp.tasf.util;

import pe.edu.pucp.tasf.model.*;

import java.util.*;

/**
 * Genera redes logisticas y solicitudes de envio sinteticas para pruebas.
 * Crea escenarios de ejemplo basados en la especificacion de Tasf.B2B.
 */
public class NetworkGenerator {

    private final Random random;

    public NetworkGenerator(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Crea una red de ejemplo con aeropuertos de America, Asia y Europa
     * y vuelos conectados segun las restricciones del problema.
     */
    public LogisticsNetwork createSampleNetwork() {
        LogisticsNetwork network = new LogisticsNetwork();

        // === AEROPUERTOS ===
        // America
        Airport lim = new Airport("LIM", "Lima", Continent.AMERICA, randCapacity());
        Airport bog = new Airport("BOG", "Bogotá", Continent.AMERICA, randCapacity());
        Airport jfk = new Airport("JFK", "New York", Continent.AMERICA, randCapacity());
        Airport gru = new Airport("GRU", "São Paulo", Continent.AMERICA, randCapacity());
        Airport mex = new Airport("MEX", "Ciudad de México", Continent.AMERICA, randCapacity());

        // Europa
        Airport mad = new Airport("MAD", "Madrid", Continent.EUROPE, randCapacity());
        Airport cdg = new Airport("CDG", "París", Continent.EUROPE, randCapacity());
        Airport fra = new Airport("FRA", "Fráncfort", Continent.EUROPE, randCapacity());
        Airport lhr = new Airport("LHR", "Londres", Continent.EUROPE, randCapacity());

        // Asia
        Airport nrt = new Airport("NRT", "Tokio", Continent.ASIA, randCapacity());
        Airport pek = new Airport("PEK", "Pekín", Continent.ASIA, randCapacity());
        Airport sin = new Airport("SIN", "Singapur", Continent.ASIA, randCapacity());
        Airport bkk = new Airport("BKK", "Bangkok", Continent.ASIA, randCapacity());

        Airport[] allAirports = {lim, bog, jfk, gru, mex, mad, cdg, fra, lhr, nrt, pek, sin, bkk};
        for (Airport a : allAirports) {
            network.addAirport(a);
        }

        int flightId = 1;

        // === VUELOS MISMO CONTINENTE (capacidad 150-250, multiples por dia) ===
        // America intra-continental
        Airport[][] americaRoutes = {
                {lim, bog}, {bog, lim}, {jfk, gru}, {gru, jfk},
                {lim, jfk}, {jfk, lim}, {bog, mex}, {mex, bog},
                {jfk, mex}, {mex, jfk}, {gru, lim}, {lim, gru},
                {mex, lim}, {lim, mex}
        };
        for (Airport[] route : americaRoutes) {
            // 2 vuelos por dia dentro del mismo continente
            for (double dep : new double[]{0.1, 0.5}) {
                int cap = randSameContCapacity();
                network.addFlight(new Flight("F" + (flightId++), route[0], route[1], cap, dep));
            }
        }

        // Europa intra-continental
        Airport[][] europeRoutes = {
                {mad, cdg}, {cdg, mad}, {fra, lhr}, {lhr, fra},
                {mad, lhr}, {lhr, mad}, {cdg, fra}, {fra, cdg}
        };
        for (Airport[] route : europeRoutes) {
            for (double dep : new double[]{0.15, 0.55}) {
                int cap = randSameContCapacity();
                network.addFlight(new Flight("F" + (flightId++), route[0], route[1], cap, dep));
            }
        }

        // Asia intra-continental
        Airport[][] asiaRoutes = {
                {nrt, pek}, {pek, nrt}, {sin, bkk}, {bkk, sin},
                {nrt, sin}, {sin, nrt}, {pek, bkk}, {bkk, pek}
        };
        for (Airport[] route : asiaRoutes) {
            for (double dep : new double[]{0.2, 0.6}) {
                int cap = randSameContCapacity();
                network.addFlight(new Flight("F" + (flightId++), route[0], route[1], cap, dep));
            }
        }

        // === VUELOS INTERCONTINENTALES (capacidad 150-400, al menos 1/dia) ===
        Airport[][] interRoutes = {
                // America <-> Europa
                {jfk, lhr}, {lhr, jfk}, {gru, mad}, {mad, gru},
                {mex, cdg}, {cdg, mex}, {lim, mad}, {mad, lim},
                {jfk, cdg}, {cdg, jfk}, {jfk, fra}, {fra, jfk},
                {bog, mad}, {mad, bog}, {gru, cdg}, {cdg, gru},
                // America <-> Asia
                {jfk, nrt}, {nrt, jfk}, {gru, sin}, {sin, gru},
                {jfk, pek}, {pek, jfk}, {mex, nrt}, {nrt, mex},
                {lim, nrt}, {nrt, lim},
                // Europa <-> Asia
                {lhr, nrt}, {nrt, lhr}, {fra, pek}, {pek, fra},
                {cdg, sin}, {sin, cdg}, {mad, bkk}, {bkk, mad},
                {lhr, sin}, {sin, lhr}, {fra, nrt}, {nrt, fra}
        };
        for (Airport[] route : interRoutes) {
            // Al menos 1 vuelo por dia; algunas rutas reciben 2
            double dep1 = 0.05 + random.nextDouble() * 0.2;
            int cap1 = randDiffContCapacity();
            network.addFlight(new Flight("F" + (flightId++), route[0], route[1], cap1, dep1));
            // Segunda salida para rutas clave
            if (random.nextDouble() < 0.5) {
                double dep2 = 0.4 + random.nextDouble() * 0.2;
                int cap2 = randDiffContCapacity();
                network.addFlight(new Flight("F" + (flightId++), route[0], route[1], cap2, dep2));
            }
        }

        return network;
    }

    /**
     * Genera una lista de solicitudes de envio aleatorias para una red dada.
     */
    public List<ShipmentRequest> generateRequests(LogisticsNetwork network, int count) {
        List<ShipmentRequest> requests = new ArrayList<>();
        List<Airport> airportList = new ArrayList<>(network.getAirports());

        for (int i = 0; i < count; i++) {
            Airport origin = airportList.get(random.nextInt(airportList.size()));
            Airport destination;
            do {
                destination = airportList.get(random.nextInt(airportList.size()));
            } while (destination.equals(origin));

            int quantity = 1 + random.nextInt(30); // 1 to 30 suitcases per request
            double creationTime = random.nextDouble() * 0.05; // created very early in the day

            requests.add(new ShipmentRequest("SR" + (i + 1), origin, destination, quantity, creationTime));
        }

        return requests;
    }

    /**
     * Genera solicitudes con demanda creciente para el escenario de colapso (E3).
     */
    public List<ShipmentRequest> generateCollapseRequests(LogisticsNetwork network,
                                                           int baseCount, int day) {
        // La demanda crece 20% por dia
        int count = (int) (baseCount * Math.pow(1.2, day));
        return generateRequests(network, count);
    }

    // Capacidad de almacen: [500, 800]
    private int randCapacity() {
        return 500 + random.nextInt(301);
    }

    // Capacidad de vuelos mismo continente: [150, 250]
    private int randSameContCapacity() {
        return 150 + random.nextInt(101);
    }

    // Capacidad de vuelos distinto continente: [150, 400]
    private int randDiffContCapacity() {
        return 150 + random.nextInt(251);
    }
}
