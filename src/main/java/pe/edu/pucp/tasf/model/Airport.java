package pe.edu.pucp.tasf.model;

/**
 * Representa un nodo aeropuerto dentro de la red logistica.
 * Cada aeropuerto pertenece a un continente y tiene un almacen con capacidad limitada.
 */
public class Airport {
    private final String code;        // e.g. "LIM", "JFK", "NRT"
    private final String city;
    private final String country;
    private final Continent continent;
    private final int utcOffsetHours;
    private final int warehouseCapacity; // [500, 800] suitcases
    private int currentStock;           // suitcases currently stored

    public Airport(String code, String city, Continent continent, int warehouseCapacity) {
        this(code, city, "", continent, 0, warehouseCapacity);
    }

    public Airport(String code, String city, String country,
                   Continent continent, int utcOffsetHours, int warehouseCapacity) {
        this.code = code;
        this.city = city;
        this.country = country;
        this.continent = continent;
        this.utcOffsetHours = utcOffsetHours;
        this.warehouseCapacity = warehouseCapacity;
        this.currentStock = 0;
    }

    public boolean canStore(int quantity) {
        return currentStock + quantity <= warehouseCapacity;
    }

    public int availableSpace() {
        return warehouseCapacity - currentStock;
    }

    public void addStock(int quantity) {
        this.currentStock += quantity;
    }

    public void removeStock(int quantity) {
        this.currentStock = Math.max(0, this.currentStock - quantity);
    }

    // Accesores
    public String getCode() { return code; }
    public String getCity() { return city; }
    public String getCountry() { return country; }
    public Continent getContinent() { return continent; }
    public int getUtcOffsetHours() { return utcOffsetHours; }
    public int getWarehouseCapacity() { return warehouseCapacity; }
    public int getCurrentStock() { return currentStock; }
    public void setCurrentStock(int stock) { this.currentStock = stock; }

    @Override
    public String toString() {
        return code + " (" + city + ", " + continent + ", GMT" + formatOffset(utcOffsetHours) + ")";
    }

    private String formatOffset(int offset) {
        return offset >= 0 ? "+" + offset : Integer.toString(offset);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Airport other)) return false;
        return code.equals(other.code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }
}
