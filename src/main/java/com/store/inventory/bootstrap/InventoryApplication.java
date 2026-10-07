package com.store.inventory.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Raíz de composición de producción: persistencia PostgreSQL, relay asíncrono de alertas y adaptador REST. */
@SpringBootApplication(scanBasePackages = {"com.store.inventory.bootstrap", "com.store.inventory.adapter.in"})
@ConfigurationPropertiesScan
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
