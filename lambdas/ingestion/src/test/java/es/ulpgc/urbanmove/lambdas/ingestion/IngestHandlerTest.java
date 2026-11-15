package es.ulpgc.urbanmove.lambdas.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import es.ulpgc.urbanmove.events.payload.UserDeactivated;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Comprueba que el handler traduce el lote de SQS a la lógica y devuelve solo los mensajes fallidos. */
class IngestHandlerTest {

    private final List<String> stored = new ArrayList<>();

    private final IngestHandler handler = new IngestHandler(new IngestJob((key, json) -> {
        if (json.contains("\"source\":\"falla\"")) {
            throw new IllegalStateException("S3 no responde");
        }
        stored.add(key);
    }));

    @Test
    void reportsOnlyTheFailedMessagesOfTheBatch() {
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(
                record("sqs-1", eventJson("rides")),
                record("sqs-2", eventJson("falla")),
                record("sqs-3", "no es un evento")));

        SQSBatchResponse response = handler.handleRequest(event, new TestContext());

        assertThat(stored).hasSize(1);
        assertThat(response.getBatchItemFailures())
                .extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("sqs-2", "sqs-3");
    }

    @Test
    void anAllGoodBatchReportsNoFailures() {
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(record("sqs-1", eventJson("rides")), record("sqs-2", eventJson("rides"))));

        SQSBatchResponse response = handler.handleRequest(event, new TestContext());

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(stored).hasSize(2);
    }

    private static SQSEvent.SQSMessage record(String messageId, String body) {
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId(messageId);
        message.setBody(body);
        return message;
    }

    private static String eventJson(String source) {
        Instant now = Instant.parse("2026-11-14T10:00:00Z");
        return EventJson.write(new EventEnvelope(UUID.randomUUID(), now, source, "UserDeactivated", "v1", "c-test",
                EventJson.mapper().valueToTree(new UserDeactivated("u-1001", now))));
    }

    /** Contexto mínimo: el handler solo usa el logger. */
    private static final class TestContext implements Context {

        @Override
        public String getAwsRequestId() {
            return "test";
        }

        @Override
        public String getLogGroupName() {
            return "test";
        }

        @Override
        public String getLogStreamName() {
            return "test";
        }

        @Override
        public String getFunctionName() {
            return "test";
        }

        @Override
        public String getFunctionVersion() {
            return "test";
        }

        @Override
        public String getInvokedFunctionArn() {
            return "test";
        }

        @Override
        public CognitoIdentity getIdentity() {
            return null;
        }

        @Override
        public ClientContext getClientContext() {
            return null;
        }

        @Override
        public int getRemainingTimeInMillis() {
            return 30_000;
        }

        @Override
        public int getMemoryLimitInMB() {
            return 512;
        }

        @Override
        public LambdaLogger getLogger() {
            return new LambdaLogger() {
                @Override
                public void log(String message) {
                }

                @Override
                public void log(byte[] message) {
                }
            };
        }
    }
}
