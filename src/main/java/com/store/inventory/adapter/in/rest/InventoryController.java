package com.store.inventory.adapter.in.rest;

import com.store.inventory.api.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Adaptador HTTP delgado sobre {@link InventoryService}. Aquí no vive ninguna regla de negocio. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @PutMapping("/products/{sku}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(
            @PathVariable @NotBlank @Size(max = 64) String sku, @Valid @RequestBody RegisterProductRequest body) {
        service.registerProduct(sku, body.category());
    }

    @PostMapping("/products/{sku}/stock")
    public AvailabilityResponse addStock(
            @PathVariable @NotBlank @Size(max = 64) String sku, @Valid @RequestBody AddStockRequest body) {
        service.addStock(sku, body.quantity());
        return new AvailabilityResponse(sku, service.available(sku));
    }

    @GetMapping("/products/{sku}/availability")
    public AvailabilityResponse availability(@PathVariable @NotBlank @Size(max = 64) String sku) {
        return new AvailabilityResponse(sku, service.available(sku));
    }

    @PostMapping("/reservations")
    public ReservationResponse reserve(@Valid @RequestBody ReserveRequest body) {
        return ReservationResponse.from(service.reserve(body.orderId(), body.sku(), body.quantity()));
    }

    @PostMapping("/reservations/{orderId}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@PathVariable @NotBlank @Size(max = 64) String orderId) {
        service.confirm(orderId);
    }
}
