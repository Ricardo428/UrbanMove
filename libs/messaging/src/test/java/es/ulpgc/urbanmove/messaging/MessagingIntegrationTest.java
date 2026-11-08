package es.ulpgc.urbanmove.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import es.ulpgc.urbanmove.common.web.CorrelationIdFilter;
import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import es.ulpgc.urbanmove.events.payload.UserRegistered;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Prueba la librería de extremo a extremo con un PostgreSQL y un LocalStack reales:
 * outbox → OutboxRelay → SNS → SQS → EventConsumer → EventHandler.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.application.name=messaging-test")
class MessagingIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.12.0"))
                    .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    static final String TOPIC_ARN;
    static final String QUEUE_URL;
    static final String DLQ_URL;

    static {
        POSTGRES.start();
        LOCALSTACK.start();
        // Credenciales ficticias de LocalStack, para los clientes que crea la autoconfiguración.
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());

        // Lo mismo que crea infra/terraform para un consumidor, con reintentos rápidos.
        try (SnsClient sns = snsClient(); SqsClient sqs = sqsClient()) {
            TOPIC_ARN = sns.createTopic(r -> r.name("test-events")).topicArn();
            DLQ_URL = sqs.createQueue(r -> r.queueName("test-consumer-dlq")).queueUrl();
            String dlqArn = sqs.getQueueAttributes(r -> r.queueUrl(DLQ_URL)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            QUEUE_URL = sqs.createQueue(r -> r.queueName("test-consumer").attributes(Map.of(
                    QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                    QueueAttributeName.REDRIVE_POLICY,
                    "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"2\"}"))).queueUrl();
            String queueArn = sqs.getQueueAttributes(r -> r.queueUrl(QUEUE_URL)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            sns.subscribe(r -> r.topicArn(TOPIC_ARN).protocol("sqs").endpoint(queueArn)
                    .attributes(Map.of("RawMessageDelivery", "true")));
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("urbanmove.messaging.topic-arn", () -> TOPIC_ARN);
        registry.add("urbanmove.messaging.queue-url", () -> QUEUE_URL);
        registry.add("urbanmove.messaging.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("urbanmove.messaging.region", LOCALSTACK::getRegion);
        registry.add("urbanmove.messaging.relay.interval", () -> "200ms");
        registry.add("urbanmove.messaging.consumer.wait-time", () -> "1s");
        registry.add("urbanmove.messaging.consumer.error-pause", () -> "1s");
    }

    @Autowired
    private EventPublisher events;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingHandler handler;

    @Test
    void anEventPublishedInATransactionReachesTheConsumerWithItsCorrelationId() {
        String userId = newUserId();

        MDC.put(CorrelationIdFilter.MDC_KEY, "c-flujo001");
        try {
            transaction.executeWithoutResult(status -> events.publish(registered(userId)));
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        await().atMost(Duration.ofSeconds(20)).until(() -> handler.successes(userId) == 1);
        EventEnvelope received = handler.received.get(userId);
        assertThat(received.type()).isEqualTo("UserRegistered");
        assertThat(received.source()).isEqualTo("messaging-test-service");
        assertThat(received.correlationId()).isEqualTo("c-flujo001");
        // El handler ve el correlationId del evento en el MDC, así que sus logs y sus eventos lo heredan.
        assertThat(handler.correlationSeen.get(userId)).isEqualTo("c-flujo001");
        assertThat(jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_events WHERE event_id = ?",
                Boolean.class, received.eventId())).isTrue();
    }

    @Test
    void theRelayLogsThePublicationWithTheCorrelationIdOfTheEvent() {
        Map<String, String> correlationByMessage = new ConcurrentHashMap<>();
        // El MDC se lee aquí, en el hilo del relay: logback lo consulta en el hilo que le pide el dato.
        AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent logLine) {
                correlationByMessage.put(logLine.getFormattedMessage(),
                        logLine.getMDCPropertyMap().getOrDefault(CorrelationIdFilter.MDC_KEY, "sin-id"));
            }
        };
        Logger relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
        capture.start();
        relayLogger.addAppender(capture);

        try {
            EventEnvelope event;
            MDC.put(CorrelationIdFilter.MDC_KEY, "c-relay001");
            try {
                event = transaction.execute(status -> events.publish(registered(newUserId())));
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }

            String published = "Evento publicado: UserRegistered " + event.eventId();
            await().atMost(Duration.ofSeconds(20)).until(() -> correlationByMessage.containsKey(published));
            assertThat(correlationByMessage.get(published)).isEqualTo("c-relay001");
        } finally {
            relayLogger.detachAppender(capture);
        }
    }

    @Test
    void aRolledBackTransactionLeavesNoEvent() {
        String userId = newUserId();

        transaction.executeWithoutResult(status -> {
            events.publish(registered(userId));
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE body -> 'payload' ->> 'userId' = ?",
                Integer.class, userId)).isZero();
    }

    @Test
    void publishingOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> events.publish(registered(newUserId())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDuplicatedEventIsProcessedOnlyOnce() {
        String userId = newUserId();
        String body = EventJson.write(EventEnvelope.of("identity-service", registered(userId), "c-dup"));

        try (SqsClient sqs = sqsClient()) {
            sqs.sendMessage(r -> r.queueUrl(QUEUE_URL).messageBody(body));
            sqs.sendMessage(r -> r.queueUrl(QUEUE_URL).messageBody(body));
        }

        await().atMost(Duration.ofSeconds(20)).until(() -> handler.successes(userId) == 1);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .until(() -> handler.attempts(userId) == 1);
    }

    @Test
    void aFailingHandlerIsRetriedAndItsFirstAttemptIsRolledBack() {
        String userId = newUserId();
        handler.failOnce.add(userId);

        try (SqsClient sqs = sqsClient()) {
            sqs.sendMessage(r -> r.queueUrl(QUEUE_URL).messageBody(
                    EventJson.write(EventEnvelope.of("identity-service", registered(userId), "c-retry"))));
        }

        await().atMost(Duration.ofSeconds(20)).until(() -> handler.successes(userId) == 1);
        assertThat(handler.attempts(userId)).isEqualTo(2);
    }

    @Test
    void aMessageThatIsNotAnEventEndsUpInTheDeadLetterQueue() {
        String body = "no es un evento " + UUID.randomUUID();

        try (SqsClient sqs = sqsClient()) {
            sqs.sendMessage(r -> r.queueUrl(QUEUE_URL).messageBody(body));

            await().atMost(Duration.ofSeconds(30)).until(() -> {
                List<Message> dead = sqs.receiveMessage(r -> r.queueUrl(DLQ_URL).maxNumberOfMessages(10)
                        .waitTimeSeconds(1)).messages();
                return dead.stream().anyMatch(message -> message.body().equals(body));
            });
        }
    }

    private static UserRegistered registered(String userId) {
        return new UserRegistered(userId, "USER", Instant.now());
    }

    private static String newUserId() {
        return "u-" + UUID.randomUUID();
    }

    private static SnsClient snsClient() {
        return SnsClient.builder().endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
                .region(Region.of(LOCALSTACK.getRegion())).credentialsProvider(credentials()).build();
    }

    private static SqsClient sqsClient() {
        return SqsClient.builder().endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
                .region(Region.of(LOCALSTACK.getRegion())).credentialsProvider(credentials()).build();
    }

    private static StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
    }

    @SpringBootApplication
    static class TestApplication {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    /** Handler de prueba: anota cada intento y puede fallar una vez a propósito para un usuario. */
    static class RecordingHandler implements EventHandler<UserRegistered> {

        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> successes = new ConcurrentHashMap<>();
        final Map<String, EventEnvelope> received = new ConcurrentHashMap<>();
        final Map<String, String> correlationSeen = new ConcurrentHashMap<>();
        final Set<String> failOnce = ConcurrentHashMap.newKeySet();

        @Override
        public Class<UserRegistered> payloadType() {
            return UserRegistered.class;
        }

        @Override
        public void handle(UserRegistered payload, EventEnvelope event) {
            attempts.computeIfAbsent(payload.userId(), id -> new AtomicInteger()).incrementAndGet();
            if (failOnce.remove(payload.userId())) {
                throw new IllegalStateException("Fallo simulado");
            }
            received.put(payload.userId(), event);
            correlationSeen.put(payload.userId(), CorrelationIdFilter.current());
            successes.computeIfAbsent(payload.userId(), id -> new AtomicInteger()).incrementAndGet();
        }

        int attempts(String userId) {
            return attempts.getOrDefault(userId, new AtomicInteger()).get();
        }

        int successes(String userId) {
            return successes.getOrDefault(userId, new AtomicInteger()).get();
        }
    }
}
