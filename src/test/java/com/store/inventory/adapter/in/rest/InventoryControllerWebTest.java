package com.store.inventory.adapter.in.rest;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.Reservation;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = InventoryController.class)
class InventoryControllerWebTest {

    /**
     * Satisface la búsqueda de Boot: la clase de la aplicación vive en {@code bootstrap}, no por encima de esta prueba.
     */
    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.context.annotation.Import({InventoryController.class, GlobalExceptionHandler.class})
    static class WebSlice {
    }

    private static final Instant EXPIRES = Instant.parse("2026-01-01T00:15:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InventoryService service;

    @Test
    void registerReturns204() throws Exception {
        mvc.perform(put("/api/v1/products/SKU-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"STANDARD\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void addStockReturnsAvailability() throws Exception {
        when(service.available("SKU-1")).thenReturn(10);
        mvc.perform(post("/api/v1/products/SKU-1/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":10}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"sku\":\"SKU-1\",\"available\":10}", true));
    }

    @Test
    void availabilityReturnsZeroForUnknownSku() throws Exception {
        when(service.available("MISSING")).thenReturn(0);
        mvc.perform(get("/api/v1/products/MISSING/availability"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"sku\":\"MISSING\",\"available\":0}", true));
    }

    @Test
    void reserveReturnsReservation() throws Exception {
        when(service.reserve("O1", "SKU-1", 3)).thenReturn(new Reservation("O1", "SKU-1", 3, EXPIRES));
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"O1\",\"sku\":\"SKU-1\",\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("O1"))
                .andExpect(jsonPath("$.sku").value("SKU-1"))
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.expiresAt").value("2026-01-01T00:15:00Z"));
    }

    @Test
    void confirmReturns204() throws Exception {
        mvc.perform(post("/api/v1/reservations/O1/confirm")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("RF mapping INVALID_REQUEST 400")
    void illegalArgumentIs400() throws Exception {
        doThrow(new IllegalArgumentException("orderId already used"))
                .when(service).reserve(anyString(), anyString(), anyInt());
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"O1\",\"sku\":\"SKU-1\",\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void orderLimitIs422() throws Exception {
        when(service.reserve(anyString(), anyString(), anyInt()))
                .thenThrow(new OrderLimitExceededException("SKU-1", 3, 2));
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"O1\",\"sku\":\"SKU-1\",\"quantity\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("ORDER_LIMIT_EXCEEDED"));
    }

    @Test
    void insufficientStockIs409() throws Exception {
        when(service.reserve(anyString(), anyString(), anyInt()))
                .thenThrow(new InsufficientStockException("SKU-1", 3, 1));
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"O1\",\"sku\":\"SKU-1\",\"quantity\":3}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void confirmWithoutReservationIs409() throws Exception {
        doThrow(new IllegalStateException("No active reservation for order O9")).when(service).confirm("O9");
        mvc.perform(post("/api/v1/reservations/O9/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NO_ACTIVE_RESERVATION"));
    }

    @Test
    void lockTimeoutIs503WithRetryAfter() throws Exception {
        when(service.reserve(anyString(), anyString(), anyInt()))
                .thenThrow(new PessimisticLockingFailureException("lock timeout"));
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"O1\",\"sku\":\"SKU-1\",\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.errorCode").value("TEMPORARILY_UNAVAILABLE"));
    }

    @Test
    void unexpectedRuntimeIs500WithoutClassOrSql() throws Exception {
        when(service.available("SKU-1")).thenThrow(new RuntimeException("org.postgresql.util.PSQLException: SQL"));
        String body = mvc.perform(get("/api/v1/products/SKU-1/availability"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("RuntimeException")
                .doesNotContain("PSQLException")
                .doesNotContain("SQL");
    }

    @Test
    void unknownRouteIs404() throws Exception {
        mvc.perform(get("/api/v1/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void unsupportedMethodIs405() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/products/SKU-1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void quantityZeroIs400() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"O1\",\"sku\":\"SKU-1\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void blankSkuIs400() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"O1\",\"sku\":\"\",\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void skuLongerThan64Is400() throws Exception {
        String sku = "S".repeat(65);
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"O1\",\"sku\":\"" + sku + "\",\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void unknownCategoryIs400ListingValidValues() throws Exception {
        String body = mvc.perform(put("/api/v1/products/SKU-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("STANDARD").contains("FLASH_SALE");
    }

    @Test
    void quantityOverflowIs400() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"O1\",\"sku\":\"SKU-1\",\"quantity\":2147483648}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void quantityTextIs400() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"O1\",\"sku\":\"SKU-1\",\"quantity\":\"many\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }
}
