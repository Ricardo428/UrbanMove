package es.ulpgc.urbanmove.lambdas.relocation;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import java.net.URI;
import java.util.Map;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

/**
 * Publica directo en el tópico, sin outbox: la Lambda no tiene base de datos, y un hotspot que no se publique se
 * vuelve a calcular en la ejecución siguiente (ADR-012).
 */
public final class SnsEventBus implements EventBus {

    private final SnsClient sns;
    private final String topicArn;

    public SnsEventBus(SnsClient sns, String topicArn) {
        this.sns = sns;
        this.topicArn = requireTopic(topicArn);
    }

    /**
     * SNS_ENDPOINT solo se define en local (LocalStack): en AWS se deja vacío y el SDK usa el SNS de verdad.
     * Región y credenciales las pone Lambda (AWS_REGION, rol de ejecución).
     */
    public static SnsEventBus fromEnvironment(Map<String, String> env) {
        // Antes de crear el cliente: sin tópico, el error debe decir eso y no que falta la región.
        String topicArn = requireTopic(env.get("TOPIC_ARN"));
        var builder = SnsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder());
        String endpoint = env.get("SNS_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return new SnsEventBus(builder.build(), topicArn);
    }

    private static String requireTopic(String topicArn) {
        if (topicArn == null || topicArn.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno TOPIC_ARN");
        }
        return topicArn;
    }

    @Override
    public void publish(EventEnvelope event) {
        // El mismo atributo que pone el relay de libs/messaging, para que las suscripciones filtradas lo vean.
        sns.publish(request -> request
                .topicArn(topicArn)
                .message(EventJson.write(event))
                .messageAttributes(Map.of("eventType", MessageAttributeValue.builder()
                        .dataType("String").stringValue(event.type()).build())));
    }
}
