package es.ulpgc.urbanmove.lambdas.relocation;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.payload.DemandHotspotDetected;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Lógica de la Lambda de recolocación, sin nada de AWS para poder probarla sin LocalStack (ADR-012).
 *
 * <ol>
 *   <li>Pide la demanda de la última hora al data mart de demanda (analytics).</li>
 *   <li>Si el data mart no está al día, para: decidir con datos viejos escondería el fallo.</li>
 *   <li>Cuenta los vehículos disponibles ahora (availability).</li>
 *   <li>Aplica la regla del enunciado y publica un DemandHotspotDetected por cada zona y tipo con déficit.</li>
 * </ol>
 *
 * Solo detecta: qué zona cede vehículos y cuántos lo decide relocation-orders al recibir el evento.
 */
public final class RelocationJob {

    static final String SOURCE = "lambda-relocation";

    private final DemandSource demand;
    private final AvailableCountsSource available;
    private final HotspotRule rule;
    private final EventBus bus;
    private final Clock clock;
    private final Duration maxDataAge;

    public RelocationJob(DemandSource demand, AvailableCountsSource available, HotspotRule rule, EventBus bus,
            Clock clock, Duration maxDataAge) {
        this.demand = demand;
        this.available = available;
        this.rule = rule;
        this.bus = bus;
        this.clock = clock;
        this.maxDataAge = maxDataAge;
    }

    /** Lee la configuración de las variables de entorno que pone Terraform (ADR-012). */
    public static RelocationJob fromEnvironment(Map<String, String> env) {
        Clock clock = Clock.systemUTC();
        ServiceToken tokens = new ServiceToken(env.get("URBANMOVE_JWT_SECRET"), clock);
        HttpClient http = HttpClient.newBuilder().connectTimeout(ServiceHttp.REQUEST_TIMEOUT).build();
        return new RelocationJob(
                new AnalyticsDemandSource(new ServiceHttp("analytics", env.get("ANALYTICS_URL"), http, tokens)),
                new AvailabilityCounter(new ServiceHttp("availability", env.get("AVAILABILITY_URL"), http, tokens)),
                new HotspotRule(intOf(env, "REQUESTS_ABOVE", 10), intOf(env, "AVAILABLE_BELOW", 3)),
                SnsEventBus.fromEnvironment(env),
                clock,
                Duration.ofMinutes(intOf(env, "MAX_DATA_AGE_MINUTES", 30)));
    }

    /** Resultado de una ejecución. */
    public record Result(int zonesWithDemand, int published, List<String> failed) {

        public String summary() {
            return "zonas con demanda=" + zonesWithDemand + ", hotspots publicados=" + published
                    + ", con error=" + failed.size();
        }
    }

    /** Lanza UpstreamException o StaleDemandException si no se puede decidir; en ese caso no publica nada. */
    public Result run(Consumer<String> log) {
        // Un identificador por ejecución: se ve en los logs de la Lambda, de analytics, de availability y en el
        // evento, así que relocation-orders y la orden que cree heredan el mismo.
        String correlationId = SOURCE + "-" + UUID.randomUUID();

        Demand current = demand.currentDemand(correlationId);
        checkUpToDate(current.dataUpTo());
        List<Hotspot> hotspots = rule.detect(current.zones(), available.count(correlationId));
        log.accept("[" + correlationId + "] zonas con demanda: " + current.zones().size()
                + ", hotspots: " + hotspots.size());

        // Si falla uno, se sigue con los demás: cada hotspot es independiente.
        int published = 0;
        List<String> failed = new ArrayList<>();
        for (Hotspot hotspot : hotspots) {
            try {
                bus.publish(EventEnvelope.of(SOURCE, eventFor(hotspot, current), correlationId));
                published++;
                log.accept("[" + correlationId + "] hotspot " + hotspot);
            } catch (RuntimeException e) {
                failed.add(hotspot.zoneId() + "/" + hotspot.vehicleType() + " (" + e.getMessage() + ")");
            }
        }
        Result result = new Result(current.zones().size(), published, List.copyOf(failed));
        log.accept("[" + correlationId + "] " + result.summary());
        return result;
    }

    private void checkUpToDate(Instant dataUpTo) {
        if (dataUpTo == null) {
            throw new StaleDemandException("El data mart de demanda no se ha construido nunca (dataUpTo = null)");
        }
        Duration age = Duration.between(dataUpTo, clock.instant());
        if (age.compareTo(maxDataAge) > 0) {
            throw new StaleDemandException("El data mart de demanda tiene " + age.toMinutes()
                    + " min de retraso (máximo " + maxDataAge.toMinutes() + ")");
        }
    }

    private static DemandHotspotDetected eventFor(Hotspot hotspot, Demand current) {
        return new DemandHotspotDetected(hotspot.zoneId(), hotspot.vehicleType(), hotspot.availableCount(),
                hotspot.requests(), current.windowStart(), current.windowEnd());
    }

    private static int intOf(Map<String, String> env, String name, int defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("La variable " + name + " no es un número: " + value);
        }
    }

    /** El data mart no está al día: contarlo como "poca demanda" escondería el fallo. */
    public static class StaleDemandException extends RuntimeException {

        public StaleDemandException(String message) {
            super(message);
        }
    }
}
