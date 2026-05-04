package pe.edu.pucp.tasf.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Utilidades para convertir entre horas locales de aeropuerto y el eje de
 * minutos UTC usado internamente por el planificador.
 */
public final class TimeUtil {
    public static final long MINUTES_PER_DAY = 24L * 60L;
    public static final LocalDate BASE_DATE = LocalDate.of(2026, 1, 1);

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private TimeUtil() {
    }

    public static int parseHourMinute(String value) {
        String[] parts = value.trim().split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    public static long localToUtcMinutes(LocalDate date, int localMinutesOfDay, int utcOffsetHours) {
        long dayIndex = ChronoUnit.DAYS.between(BASE_DATE, date);
        return dayIndex * MINUTES_PER_DAY + localMinutesOfDay - utcOffsetHours * 60L;
    }

    public static LocalDateTime toLocalDateTime(long utcMinutes, int utcOffsetHours) {
        long localMinutes = utcMinutes + utcOffsetHours * 60L;
        long dayIndex = Math.floorDiv(localMinutes, MINUTES_PER_DAY);
        int minuteOfDay = (int) Math.floorMod(localMinutes, MINUTES_PER_DAY);
        return BASE_DATE.plusDays(dayIndex).atTime(minuteOfDay / 60, minuteOfDay % 60);
    }

    public static String formatLocalDateTime(long utcMinutes, int utcOffsetHours) {
        return DATE_TIME_FORMAT.format(toLocalDateTime(utcMinutes, utcOffsetHours));
    }

    public static double minutesToDays(long minutes) {
        return minutes / (double) MINUTES_PER_DAY;
    }

    public static String formatDurationDays(long minutes) {
        return String.format("%.2f d", minutesToDays(minutes));
    }
}
