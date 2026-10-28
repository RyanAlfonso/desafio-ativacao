package com.example.activation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dispara o dispatcher periodicamente. Desligado nos testes para controlar o momento do envio. */
@Component
@ConditionalOnProperty(name = "app.dispatch.enabled", havingValue = "true")
class DispatchScheduler {

    private final ActivationDispatcher dispatcher;

    DispatchScheduler(ActivationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${app.dispatch.poll-interval}")
    void poll() {
        dispatcher.dispatchDue();
    }
}
