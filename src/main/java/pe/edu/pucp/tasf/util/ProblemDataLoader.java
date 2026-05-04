package pe.edu.pucp.tasf.util;

import pe.edu.pucp.tasf.model.Airport;
import pe.edu.pucp.tasf.model.Continent;
import pe.edu.pucp.tasf.model.Flight;
import pe.edu.pucp.tasf.model.LogisticsNetwork;
import pe.edu.pucp.tasf.model.ShipmentRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Carga el catalogo fijo de aeropuertos, los planes de vuelo diarios
 * repetitivos y las solicitudes de envio desde el ZIP del curso.
 */
public class ProblemDataLoader {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern SPACES = Pattern.compile("\\s{2,}");

    public LogisticsNetwork loadNetwork(Path airportsPath, Path flightsPath) throws IOException {
        LogisticsNetwork network = new LogisticsNetwork();

        Map<String, Airport> airportByCode = loadAirports(airportsPath);
        for (Airport airport : airportByCode.values()) {
            network.addAirport(airport);
        }

        Map<String, Integer> duplicateIds = new HashMap<>();
        for (String rawLine : Files.readAllLines(flightsPath, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("-");
            if (parts.length < 5) {
                throw new IOException("Formato invalido en plan de vuelo: " + line);
            }

            Airport origin = airportByCode.get(parts[0]);
            Airport destination = airportByCode.get(parts[1]);
            if (origin == null || destination == null) {
                throw new IOException("Aeropuerto desconocido en plan de vuelo: " + line);
            }

            String scheduleId = parts[0] + "-" + parts[1] + "-" + parts[2];
            int occurrence = duplicateIds.merge(scheduleId, 1, Integer::sum);
            if (occurrence > 1) {
                scheduleId = scheduleId + "#" + occurrence;
            }

            int departureLocalMinutes = TimeUtil.parseHourMinute(parts[2]);
            int arrivalLocalMinutes = TimeUtil.parseHourMinute(parts[3]);
            int capacity = Integer.parseInt(parts[4]);

            network.addFlight(new Flight(
                    scheduleId,
                    origin,
                    destination,
                    capacity,
                    departureLocalMinutes,
                    arrivalLocalMinutes
            ));
        }

        return network;
    }

    public LocalDate findEarliestShipmentDate(Path shipmentsZipPath) throws IOException {
        LocalDate earliest = null;

        try (ZipFile zipFile = new ZipFile(shipmentsZipPath.toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : listRegularEntries(zipFile)) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        zipFile.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    LocalDate date = parseShipmentDate(line.trim());
                    if (earliest == null || date.isBefore(earliest)) {
                        earliest = date;
                    }
                }
            }
        }

        if (earliest == null) {
            throw new IOException("No se encontro ninguna fecha valida en " + shipmentsZipPath);
        }
        return earliest;
    }

    public Map<LocalDate, List<ShipmentRequest>> loadShipmentsByDate(
            Path shipmentsZipPath,
            LogisticsNetwork network,
            LocalDate startDate,
            int days
    ) throws IOException {
        Map<LocalDate, List<ShipmentRequest>> requestsByDate = new TreeMap<>();
        Set<LocalDate> datesToLoad = new TreeSet<>();
        for (int i = 0; i < days; i++) {
            datesToLoad.add(startDate.plusDays(i));
        }

        try (ZipFile zipFile = new ZipFile(shipmentsZipPath.toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : listRegularEntries(zipFile)) {
                String originCode = extractOriginFromEntry(entry.getName());
                Airport origin = network.getAirport(originCode);
                if (origin == null) {
                    continue;
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        zipFile.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) {
                            continue;
                        }

                        ShipmentRequest request = parseShipment(trimmed, origin, network);
                        LocalDate requestDate = TimeUtil.toLocalDateTime(
                                request.getCreationTimeMinutes(),
                                origin.getUtcOffsetHours()).toLocalDate();
                        if (!datesToLoad.contains(requestDate)) {
                            continue;
                        }
                        requestsByDate.computeIfAbsent(requestDate, key -> new ArrayList<>()).add(request);
                    }
                }
            }
        }

        for (List<ShipmentRequest> requests : requestsByDate.values()) {
            requests.sort(Comparator
                    .comparingLong(ShipmentRequest::getCreationTimeMinutes)
                    .thenComparing(ShipmentRequest::getId));
        }

        return requestsByDate;
    }

    public List<ShipmentRequest> aggregateRequests(List<ShipmentRequest> rawRequests, int maxBatchSize) {
        if (maxBatchSize <= 0) {
            return new ArrayList<>(rawRequests);
        }

        Map<AggregateKey, Integer> totals = new LinkedHashMap<>();
        Map<AggregateKey, ShipmentRequest> representatives = new LinkedHashMap<>();
        for (ShipmentRequest request : rawRequests) {
            AggregateKey key = new AggregateKey(
                    request.getOrigin().getCode(),
                    request.getDestination().getCode(),
                    request.getCreationTimeMinutes()
            );
            totals.merge(key, request.getQuantity(), Integer::sum);
            representatives.putIfAbsent(key, request);
        }

        List<ShipmentRequest> aggregated = new ArrayList<>();
        for (Map.Entry<AggregateKey, Integer> entry : totals.entrySet()) {
            ShipmentRequest representative = representatives.get(entry.getKey());
            Airport origin = representative.getOrigin();
            Airport destination = representative.getDestination();

            int remaining = entry.getValue();
            int part = 1;
            while (remaining > 0) {
                int qty = Math.min(maxBatchSize, remaining);
                String id = String.format("AGG-%s-%s-%d-%03d",
                        entry.getKey().originCode(),
                        entry.getKey().destinationCode(),
                        entry.getKey().creationTimeMinutes(),
                        part++);
                aggregated.add(new ShipmentRequest(
                        id,
                        origin,
                        destination,
                        qty,
                        entry.getKey().creationTimeMinutes(),
                        "AGG"
                ));
                remaining -= qty;
            }
        }

        aggregated.sort(Comparator
                .comparingLong(ShipmentRequest::getCreationTimeMinutes)
                .thenComparing(ShipmentRequest::getId));
        return aggregated;
    }

    private Map<String, Airport> loadAirports(Path airportsPath) throws IOException {
        Map<String, Airport> airports = new LinkedHashMap<>();
        Continent currentContinent = null;

        for (String rawLine : Files.readAllLines(airportsPath, StandardCharsets.UTF_16)) {
            String line = rawLine.replace("\uFEFF", "").trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("America del Sur")) {
                currentContinent = Continent.AMERICA;
                continue;
            }
            if (line.startsWith("Europa")) {
                currentContinent = Continent.EUROPE;
                continue;
            }
            if (line.startsWith("Asia")) {
                currentContinent = Continent.ASIA;
                continue;
            }
            if (!Character.isDigit(line.charAt(0))) {
                continue;
            }

            String withoutCoords = line.split("Latitude:")[0].trim();
            String[] parts = SPACES.split(withoutCoords);
            if (parts.length < 7 || currentContinent == null) {
                continue;
            }

            String code = parts[1].trim();
            String city = parts[2].trim();
            String country = parts[3].trim();
            int utcOffsetHours = Integer.parseInt(parts[5].trim());
            int warehouseCapacity = Integer.parseInt(parts[6].trim());

            airports.put(code, new Airport(
                    code,
                    city,
                    country,
                    currentContinent,
                    utcOffsetHours,
                    warehouseCapacity
            ));
        }

        if (airports.isEmpty()) {
            throw new IOException("No se pudo cargar ningun aeropuerto desde " + airportsPath);
        }
        return airports;
    }

    private ShipmentRequest parseShipment(String line, Airport origin, LogisticsNetwork network) throws IOException {
        String[] parts = line.split("-");
        if (parts.length != 7) {
            throw new IOException("Formato invalido de envio: " + line);
        }

        LocalDate date = LocalDate.parse(parts[1], DATE_FORMAT);
        int localMinutesOfDay = Integer.parseInt(parts[2]) * 60 + Integer.parseInt(parts[3]);
        Airport destination = network.getAirport(parts[4]);
        if (destination == null) {
            throw new IOException("Destino desconocido en envio: " + line);
        }
        int quantity = Integer.parseInt(parts[5]);
        long creationUtcMinutes = TimeUtil.localToUtcMinutes(
                date,
                localMinutesOfDay,
                origin.getUtcOffsetHours()
        );

        return new ShipmentRequest(parts[0], origin, destination, quantity, creationUtcMinutes, parts[6]);
    }

    private LocalDate parseShipmentDate(String line) {
        String[] parts = line.split("-");
        return LocalDate.parse(parts[1], DATE_FORMAT);
    }

    private List<ZipEntry> listRegularEntries(ZipFile zipFile) {
        List<ZipEntry> entries = new ArrayList<>();
        zipFile.stream()
                .filter(entry -> !entry.isDirectory())
                .forEach(entries::add);
        return entries;
    }

    private String extractOriginFromEntry(String entryName) {
        return entryName
                .replace("_envios_", "")
                .replace(".txt", "")
                .replace("_", "");
    }

    private record AggregateKey(String originCode, String destinationCode, long creationTimeMinutes) { }
}
