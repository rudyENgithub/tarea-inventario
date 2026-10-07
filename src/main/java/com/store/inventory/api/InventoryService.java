package com.store.inventory.api;

/**
 * Contrato público del servicio de inventario. No modificar este archivo.
 */
public interface InventoryService {

    /**
     * Registra un producto y su categoría. Un producto debe estar registrado antes de agregarle stock.
     */
    void registerProduct(String sku, ProductCategory category);

    /**
     * Agrega unidades de un producto registrado al almacén.
     *
     * @throws IllegalArgumentException si la cantidad es cero o negativa, o el producto no está registrado
     */
    void addStock(String sku, int quantity);

    /**
     * Reserva unidades de un producto para una orden. Cada orden reserva un único producto.
     * La reserva expira según la categoría del producto a menos que se confirme.
     *
     * @throws IllegalArgumentException si la cantidad es cero o negativa
     * @throws OrderLimitExceededException si la categoría no permite tantas unidades por orden
     * @throws InsufficientStockException si no hay unidades suficientes (los productos desconocidos no tienen)
     */
    Reservation reserve(String orderId, String sku, int quantity);

    /**
     * Confirma la reserva de una orden pagada. Las unidades confirmadas se venden y nunca vuelven al stock.
     *
     * @throws IllegalStateException si la orden no tiene una reserva activa
     */
    void confirm(String orderId);

    /**
     * Unidades que aún se pueden reservar: stock menos las reservas activas (no expiradas).
     * Devuelve 0 para productos desconocidos.
     */
    int available(String sku);
}
