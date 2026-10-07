package com.store.inventory.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.store.inventory.application.port.out.AlertChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingAlertChannelTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingAlertChannel.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("RF-A1: logs a WARN with sku and available units")
    void logsAWarning() {
        new LoggingAlertChannel().onLowStock("SKU-1", 4);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).isEqualTo("LOW_STOCK sku=SKU-1 available=4");
    }

    @Test
    void isNamedLog() {
        assertThat(new LoggingAlertChannel().name()).isEqualTo("log");
    }

    @Test
    @DisplayName("OCP/LSP: a brand new channel joins the composite without modifying it")
    void newChannelPlugsIn() {
        List<String> received = new ArrayList<>();
        AlertChannel custom = new AlertChannel() {
            @Override
            public String name() {
                return "custom";
            }

            @Override
            public void onLowStock(String sku, int availableUnits) {
                received.add(sku + "/" + availableUnits);
            }
        };
        var composite = new CompositeStockAlertListener(List.of(new LoggingAlertChannel(), custom));

        composite.onLowStock("SKU-2", 1);

        assertThat(received).containsExactly("SKU-2/1");
        assertThat(appender.list).hasSize(1);
    }

    @Test
    void ofExposesNameAndDelegates() {
        List<String> received = new ArrayList<>();
        AlertChannel channel = AlertChannel.of("x", (sku, available) -> received.add(sku));

        channel.onLowStock("S", 1);

        assertThat(channel.name()).isEqualTo("x");
        assertThat(received).containsExactly("S");
    }
}
