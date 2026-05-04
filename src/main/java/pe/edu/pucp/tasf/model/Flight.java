package pe.edu.pucp.tasf.model;

import pe.edu.pucp.tasf.util.TimeUtil;

/**
 * Representa un horario de vuelo diario repetitivo o una de sus salidas
 * concretas ya instanciadas.
 */
public class Flight {
    private final String scheduleId;
    private final String instanceId;
    private final Airport origin;
    private final Airport destination;
    private final int capacity;
    private final int departureLocalMinutes;
    private final int arrivalLocalMinutes;
    private final int durationMinutes;
    private final long departureUtcMinutes;
    private final long arrivalUtcMinutes;
    private boolean cancelled;

    public Flight(String scheduleId, Airport origin, Airport destination,
                  int capacity, int departureLocalMinutes, int arrivalLocalMinutes) {
        this(scheduleId, scheduleId, origin, destination, capacity,
                departureLocalMinutes, arrivalLocalMinutes,
                computeDurationMinutes(origin, destination, departureLocalMinutes, arrivalLocalMinutes),
                -1L, -1L, false);
    }

    /**
     * Constructor de compatibilidad mantenido para el generador sintetico.
     */
    public Flight(String scheduleId, Airport origin, Airport destination, int capacity, double departureTime) {
        this(scheduleId, origin, destination, capacity,
                fractionOfDayToMinutes(departureTime),
                fractionOfDayToMinutes((departureTime
                        + (origin.getContinent() == destination.getContinent() ? 0.5 : 1.0)) % 1.0));
    }

    private Flight(String scheduleId, String instanceId,
                   Airport origin, Airport destination,
                   int capacity, int departureLocalMinutes, int arrivalLocalMinutes,
                   int durationMinutes, long departureUtcMinutes, long arrivalUtcMinutes,
                   boolean cancelled) {
        this.scheduleId = scheduleId;
        this.instanceId = instanceId;
        this.origin = origin;
        this.destination = destination;
        this.capacity = capacity;
        this.departureLocalMinutes = departureLocalMinutes;
        this.arrivalLocalMinutes = arrivalLocalMinutes;
        this.durationMinutes = durationMinutes;
        this.departureUtcMinutes = departureUtcMinutes;
        this.arrivalUtcMinutes = arrivalUtcMinutes;
        this.cancelled = cancelled;
    }

    public boolean isSameContinent() {
        return origin.getContinent() == destination.getContinent();
    }

    public boolean isTemplate() {
        return departureUtcMinutes < 0;
    }

    public Flight instantiateAfter(long earliestUtcMinutes) {
        long departureUtcMinuteOfDay = normalizeMinuteOfDay(departureLocalMinutes
                - origin.getUtcOffsetHours() * 60L);
        long baseDay = Math.floorDiv(earliestUtcMinutes, TimeUtil.MINUTES_PER_DAY);
        long concreteDeparture = baseDay * TimeUtil.MINUTES_PER_DAY + departureUtcMinuteOfDay;
        if (concreteDeparture < earliestUtcMinutes) {
            concreteDeparture += TimeUtil.MINUTES_PER_DAY;
        }
        long concreteArrival = concreteDeparture + durationMinutes;
        long localDepartureDay = Math.floorDiv(
                concreteDeparture + origin.getUtcOffsetHours() * 60L,
                TimeUtil.MINUTES_PER_DAY);

        return new Flight(
                scheduleId,
                scheduleId + "@" + localDepartureDay,
                origin,
                destination,
                capacity,
                departureLocalMinutes,
                arrivalLocalMinutes,
                durationMinutes,
                concreteDeparture,
                concreteArrival,
                cancelled
        );
    }

    public double getTransitTime() {
        return durationMinutes / (double) TimeUtil.MINUTES_PER_DAY;
    }

    public long getDepartureUtcMinutes() {
        return departureUtcMinutes;
    }

    public long getArrivalUtcMinutes() {
        return arrivalUtcMinutes;
    }

    public double getDepartureTime() {
        return isTemplate()
                ? departureLocalMinutes / (double) TimeUtil.MINUTES_PER_DAY
                : TimeUtil.minutesToDays(departureUtcMinutes);
    }

    public double getArrivalTime() {
        return isTemplate()
                ? arrivalLocalMinutes / (double) TimeUtil.MINUTES_PER_DAY
                : TimeUtil.minutesToDays(arrivalUtcMinutes);
    }

    public int remainingCapacity() {
        return capacity;
    }

    // Accesores y mutadores
    public String getId() { return instanceId; }
    public String getScheduleId() { return scheduleId; }
    public String getInstanceId() { return instanceId; }
    public Airport getOrigin() { return origin; }
    public Airport getDestination() { return destination; }
    public int getCapacity() { return capacity; }
    public int getDepartureLocalMinutes() { return departureLocalMinutes; }
    public int getArrivalLocalMinutes() { return arrivalLocalMinutes; }
    public int getDurationMinutes() { return durationMinutes; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    @Override
    public String toString() {
        return instanceId + ": " + origin.getCode() + " -> " + destination.getCode()
                + " [cap=" + capacity
                + ", dep=" + TimeUtil.formatLocalDateTime(
                isTemplate() ? 0L : departureUtcMinutes, origin.getUtcOffsetHours())
                + ", arr=" + TimeUtil.formatLocalDateTime(
                isTemplate() ? durationMinutes : arrivalUtcMinutes, destination.getUtcOffsetHours())
                + "]"
                + (cancelled ? " CANCELLED" : "");
    }

    private static int computeDurationMinutes(Airport origin, Airport destination,
                                              int departureLocalMinutes, int arrivalLocalMinutes) {
        int departureUtc = normalizeMinuteOfDay(departureLocalMinutes - origin.getUtcOffsetHours() * 60L);
        int arrivalUtc = normalizeMinuteOfDay(arrivalLocalMinutes - destination.getUtcOffsetHours() * 60L);
        while (arrivalUtc < departureUtc) {
            arrivalUtc += (int) TimeUtil.MINUTES_PER_DAY;
        }
        return arrivalUtc - departureUtc;
    }

    private static int fractionOfDayToMinutes(double fraction) {
        return (int) Math.round(fraction * TimeUtil.MINUTES_PER_DAY) % (int) TimeUtil.MINUTES_PER_DAY;
    }

    private static int normalizeMinuteOfDay(long minute) {
        return (int) Math.floorMod(minute, TimeUtil.MINUTES_PER_DAY);
    }
}
