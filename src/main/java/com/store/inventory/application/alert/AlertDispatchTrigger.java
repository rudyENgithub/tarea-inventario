package com.store.inventory.application.alert;

/**
 * Avisa a la maquinaria de entrega que se encolaron nuevas alertas, sin saber cómo se entregan.
 *
 * <p>Contrato: nunca lanza excepciones, es barato y puede ser asíncrono.
 */
public interface AlertDispatchTrigger {

    void alertsEnqueued();
}
