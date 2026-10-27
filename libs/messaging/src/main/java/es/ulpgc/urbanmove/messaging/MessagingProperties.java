package es.ulpgc.urbanmove.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del bus de eventos (prefijo urbanmove.messaging).
 *
 * @param source   valor del campo "source" de los eventos; por defecto, el nombre de la aplicación + "-service"
 * @param topicArn topic SNS al que se publica; vacío = los eventos se quedan en la outbox hasta que se configure
 * @param queueUrl cola SQS propia del servicio; vacía = el servicio no consume eventos
 * @param endpoint URL de LocalStack; vacío en AWS real
 * @param region   región de AWS
 */
@ConfigurationProperties(prefix = "urbanmove.messaging")
public record MessagingProperties(
        String source,
        String topicArn,
        String queueUrl,
        String endpoint,
        @DefaultValue("us-east-1") String region,
        @DefaultValue Relay relay,
        @DefaultValue Consumer consumer) {

    /**
     * @param interval   pausa entre dos pasadas por la outbox
     * @param batchSize  eventos que se leen de la outbox por transacción
     * @param maxBackoff espera máxima entre reintentos de un evento que no se pudo publicar
     */
    public record Relay(
            @DefaultValue("1s") Duration interval,
            @DefaultValue("50") int batchSize,
            @DefaultValue("5m") Duration maxBackoff) {
    }

    /**
     * @param waitTime    long polling de SQS (máximo 20 s)
     * @param maxMessages mensajes por lectura (máximo 10)
     * @param errorPause  pausa tras un error al leer de la cola, para no reintentar en bucle si SQS no responde
     */
    public record Consumer(
            @DefaultValue("10s") Duration waitTime,
            @DefaultValue("10") int maxMessages,
            @DefaultValue("5s") Duration errorPause) {
    }
}
