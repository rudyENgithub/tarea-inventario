package com.store.inventory.application.port.out;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.stock.StockReservation;
import java.time.Instant;
import java.util.Optional;

/**
 * Puerto de persistencia del inventario. Expresa la sección crítica atómica por SKU: el adaptador carga el producto,
 * ejecuta la operación del llamador y persiste el resultado como una unidad, garantizando exclusión mutua por SKU
 * (lock en proceso en memoria, lock de fila en una base de datos).
 */
public interface InventoryRepository {

    /**
     * Registra el producto si no existe (stock cero, latch de alerta armado). Atómico.
     *
     * @return la categoría efectiva: la existente cuando el SKU ya estaba registrado
     */
    ProductCategory registerIfAbsent(String sku, ProductCategory category);

    Optional<StockReservation> findReservation(String orderId);

    /** {@code stock - sum(reservas ACTIVE con expiresAt > now)}; 0 si el SKU no existe. Lectura consistente. */
    int availableUnits(String sku, Instant now);

    /**
     * Ejecuta {@code operation} dentro de la sección crítica del SKU.
     *
     * <p>Si {@code operation} lanza una excepción, no se persiste nada (ni estado, ni cambios, ni alertas) y la
     * excepción se propaga sin envolver. Si retorna, el estado escalar, los cambios de reservas (primero las bajas,
     * luego los upserts) y las alertas se persisten como una unidad. Una reserva cuyo id de orden está ligado a otro
     * SKU hace fallar la llamada con {@link OrderIdConflictException} y no persiste nada.
     *
     * @return vacío, sin ejecutar la operación, cuando el SKU no está registrado
     */
    <R> Optional<R> executeOnProduct(String sku, StockOperation<R> operation);

    /**
     * Igual que {@link #executeOnProduct(String, StockOperation)}, cargando además la reserva de {@code orderId}.
     */
    <R> Optional<R> executeOnProduct(String sku, String orderId, StockOperation<R> operation);
}
