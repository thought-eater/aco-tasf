package pe.edu.pucp.tasf.model;

import pe.edu.pucp.tasf.util.TimeUtil;

/**
 * Representa una solicitud de envio de maletas (demanda).
 * dk = (ok, sk, qk, TWk) segun la formulacion del problema.
 */
public class ShipmentRequest {
    private final String id;
    private final Airport origin;       // ok
    private final Airport destination;  // sk
    private final int quantity;         // qk - number of suitcases
    private final long deadlineMinutes; // TWk - max delivery time in minutes
    private final long creationTimeMinutes;
    private final String clientId;

    public ShipmentRequest(String id, Airport origin, Airport destination,
                           int quantity, double creationTime) {
        this(id, origin, destination, quantity,
                Math.round(creationTime * TimeUtil.MINUTES_PER_DAY), null);
    }

    public ShipmentRequest(String id, Airport origin, Airport destination,
                           int quantity, long creationTimeMinutes, String clientId) {
        this.id = id;
        this.origin = origin;
        this.destination = destination;
        this.quantity = quantity;
        this.creationTimeMinutes = creationTimeMinutes;
        this.clientId = clientId;

        // Plazo: 1 dia mismo continente, 2 dias distinto continente
        boolean sameCont = origin.getContinent() == destination.getContinent();
        this.deadlineMinutes = sameCont ? TimeUtil.MINUTES_PER_DAY : 2 * TimeUtil.MINUTES_PER_DAY;
    }

    public boolean isOverdue(long currentTimeMinutes) {
        return (currentTimeMinutes - creationTimeMinutes) > deadlineMinutes;
    }

    public long timeRemainingMinutes(long currentTimeMinutes) {
        return deadlineMinutes - (currentTimeMinutes - creationTimeMinutes);
    }

    public boolean isSameContinent() {
        return origin.getContinent() == destination.getContinent();
    }

    // Accesores
    public String getId() { return id; }
    public Airport getOrigin() { return origin; }
    public Airport getDestination() { return destination; }
    public int getQuantity() { return quantity; }
    public double getDeadline() { return TimeUtil.minutesToDays(deadlineMinutes); }
    public long getDeadlineMinutes() { return deadlineMinutes; }
    public double getCreationTime() { return TimeUtil.minutesToDays(creationTimeMinutes); }
    public long getCreationTimeMinutes() { return creationTimeMinutes; }
    public String getClientId() { return clientId; }

    @Override
    public String toString() {
        return id + ": " + origin.getCode() + " -> " + destination.getCode()
                + " [qty=" + quantity + ", deadline=" + getDeadline() + "d]";
    }
}
