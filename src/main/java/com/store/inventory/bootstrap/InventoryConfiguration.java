package com.store.inventory.bootstrap;

import com.store.inventory.adapter.out.notification.CompositeStockAlertListener;
import com.store.inventory.adapter.out.notification.LoggingAlertChannel;
import com.store.inventory.adapter.out.notification.SafeStockAlertListener;
import com.store.inventory.adapter.out.persistence.jdbc.JdbcAlertOutbox;
import com.store.inventory.adapter.out.persistence.jdbc.JdbcInventoryRepository;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.DefaultInventoryService;
import com.store.inventory.application.alert.AlertDispatchTrigger;
import com.store.inventory.application.alert.AlertDispatcher;
import com.store.inventory.application.alert.AlertRelay;
import com.store.inventory.application.alert.AlertRetryPolicy;
import com.store.inventory.application.alert.AsyncAlertDispatchTrigger;
import com.store.inventory.application.port.out.AlertChannel;
import com.store.inventory.application.port.out.AlertOutbox;
import com.store.inventory.application.port.out.InventoryRepository;
import com.store.inventory.domain.alert.LowStockDetector;
import com.store.inventory.domain.alert.LowStockThreshold;
import com.store.inventory.domain.policy.CategoryPolicyRegistry;
import com.store.inventory.domain.policy.DefaultCategoryPolicies;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ensambla el hexágono para el modo PostgreSQL. Un nuevo canal de notificación es una clase que implemente
 * {@link AlertChannel} más un {@code @Bean} aquí (o en cualquier configuración): {@code List<AlertChannel>} lo
 * recoge automáticamente.
 */
@Configuration(proxyBeanMethods = false)
public class InventoryConfiguration {

    /** El único lugar donde se crea el reloj del sistema (regla del contrato C-4). Las pruebas lo sustituyen. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CategoryPolicyRegistry categoryPolicyRegistry() {
        return CategoryPolicyRegistry.of(DefaultCategoryPolicies.all());
    }

    @Bean
    LowStockDetector lowStockDetector(InventoryProperties properties) {
        return new LowStockDetector(new LowStockThreshold(properties.lowStock().threshold()));
    }

    /** El repositorio deriva su propia copia READ COMMITTED de esta plantilla. */
    @Bean
    InventoryRepository inventoryRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager,
            InventoryProperties properties) {
        return new JdbcInventoryRepository(jdbc, new TransactionTemplate(transactionManager),
                properties.database().lockTimeout());
    }

    @Bean
    AlertOutbox alertOutbox(JdbcClient jdbc) {
        return new JdbcAlertOutbox(jdbc);
    }

    @Bean
    AlertChannel loggingAlertChannel() {
        return new SafeStockAlertListener(new LoggingAlertChannel());
    }

    @Bean
    @Primary
    StockAlertListener stockAlertListener(List<AlertChannel> channels) {
        return new CompositeStockAlertListener(channels);
    }

    @Bean
    AlertDispatcher alertDispatcher(AlertOutbox outbox, StockAlertListener listener, Clock clock,
            InventoryProperties properties) {
        InventoryProperties.Dispatch dispatch = properties.alerts().dispatch();
        var retryPolicy = new AlertRetryPolicy(dispatch.maxAttempts(), dispatch.baseDelay(), dispatch.maxDelay());
        return new AlertDispatcher(outbox, listener, clock, retryPolicy, dispatch.batchSize(), dispatch.lease());
    }

    /**
     * Arranca después de que Flyway migre el esquema y se detiene de forma ordenada, terminando la entrega en curso.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @DependsOn("flywayInitializer")
    AlertRelay alertRelay(AlertDispatcher dispatcher, InventoryProperties properties) {
        return new AlertRelay(dispatcher::dispatchPending, properties.alerts().relay().pollInterval());
    }

    @Bean
    AlertDispatchTrigger alertDispatchTrigger(AlertRelay relay) {
        return new AsyncAlertDispatchTrigger(relay);
    }

    @Bean
    InventoryService inventoryService(InventoryRepository repository, CategoryPolicyRegistry policies,
            LowStockDetector detector, AlertDispatchTrigger trigger, Clock clock) {
        return new DefaultInventoryService(repository, policies, detector, trigger, clock);
    }
}
