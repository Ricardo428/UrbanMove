package es.ulpgc.urbanmove.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ulpgc.urbanmove.events.payload.RideStarted;
import es.ulpgc.urbanmove.events.payload.UserRegistered;
import es.ulpgc.urbanmove.events.payload.VehiclePurchased;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EventContractTest {

    /** Los ejemplos de docs/events.md son el contrato publicado: el código no puede divergir de ellos. */
    private static final Path EVENTS_DOC = Path.of("../../docs/events.md");
    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    @Test
    void everyExampleInTheDocsMatchesItsPayloadClassExactly() throws IOException {
        List<JsonNode> examples = examplesFromDocs();
        ObjectMapper strict = EventJson.mapper().copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        for (JsonNode example : examples) {
            EventEnvelope event = EventJson.read(example.toString());
            EventType type = EventType.fromWireName(event.type()).orElseThrow(
                    () -> new AssertionError("Tipo de evento no registrado en EventType: " + event.type()));

            // Ningún campo del ejemplo sobra en el record...
            Object payload = strict.treeToValue(event.payload(), type.payloadClass());
            // ...y ninguno del record falta en el ejemplo.
            JsonNode roundTrip = EventJson.mapper().valueToTree(payload);
            assertThat(fieldNames(roundTrip)).as(event.type()).isEqualTo(fieldNames(event.payload()));
            assertThat(event.version()).as(event.type()).isEqualTo(type.version());
        }
    }

    @Test
    void theDocsHaveAnExampleForEveryEventType() throws IOException {
        Set<String> documented = examplesFromDocs().stream()
                .map(example -> example.get("type").asText())
                .collect(Collectors.toSet());

        assertThat(documented).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(EventType.values()).map(EventType::wireName).toList());
    }

    @Test
    void anEventSurvivesARoundTrip() {
        var purchased = new VehiclePurchased("v-0042", VehicleType.SCOOTER, "Xiaomi Pro 4",
                LocalDate.parse("2026-10-13"), "zone-centro");

        EventEnvelope event = EventEnvelope.of("inventory-service", purchased, "c-1b77e9a0");
        String json = EventJson.write(event);
        EventEnvelope read = EventJson.read(json);

        assertThat(json).contains("\"type\":\"VehiclePurchased\"", "\"purchaseDate\":\"2026-10-13\"");
        assertThat(read).isEqualTo(event);
        assertThat(read.payloadAs(VehiclePurchased.class)).isEqualTo(purchased);
        assertThat(read.version()).isEqualTo("v1");
    }

    @Test
    void aMissingCorrelationIdIsGenerated() {
        EventEnvelope event = EventEnvelope.of("identity-service",
                new UserRegistered("u-1", "USER", Instant.now()), null);

        assertThat(event.correlationId()).matches("c-[0-9a-f]{8}");
    }

    @Test
    void unknownFieldsAreIgnoredSoProducersCanAddThem() {
        String json = """
                {"eventId":"3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b06","timestamp":"2026-10-13T10:30:00Z",
                 "source":"rides-service","type":"RideStarted","version":"v1","correlationId":"c-1",
                 "newEnvelopeField":1,
                 "payload":{"rideId":"ride-1","vehicleId":"v-1","userId":"u-1","reservationId":null,
                            "originZoneId":"zone-centro","startTime":"2026-10-13T10:30:00Z","newField":"x"}}
                """;

        RideStarted payload = EventJson.read(json).payloadAs(RideStarted.class);

        assertThat(payload.reservationId()).isNull();
        assertThat(payload.originZoneId()).isEqualTo("zone-centro");
    }

    @Test
    void aMessageWithoutTheEnvelopeFieldsIsRejected() {
        assertThatThrownBy(() -> EventJson.read("{\"type\":\"RideStarted\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventJson.read("esto no es json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<JsonNode> examplesFromDocs() throws IOException {
        Matcher matcher = JSON_BLOCK.matcher(Files.readString(EVENTS_DOC));
        List<JsonNode> examples = new ArrayList<>();
        while (matcher.find()) {
            JsonNode node = EventJson.mapper().readTree(matcher.group(1));
            if (node.has("eventId")) {
                examples.add(node);
            }
        }
        assertThat(examples).as("ejemplos en " + EVENTS_DOC).isNotEmpty();
        return examples;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
