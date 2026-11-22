package es.ulpgc.urbanmove.relocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ulpgc.urbanmove.relocation.availability.StateOutcome;
import es.ulpgc.urbanmove.relocation.availability.VehicleOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/**
 * Dos peticiones a la vez, en dos hilos, contra el servicio y un PostgreSQL reales. Donde importa que lleguen juntas
 * al punto crítico, el doble de availability retiene el GET hasta que las dos han llegado a él (CyclicBarrier): así
 * las dos pasan la comprobación y chocan en la base de datos. Se comprueban invariantes (un ganador, un evento), no
 * qué hilo gana.
 */
@SuppressWarnings("rawtypes")
class ConcurrencyTest extends AbstractOrderApiTest {

    @Test
    void twoOperatorsPickUpTheSameOrderAtOnceAndOnlyOneGetsIt() {
        Order order = newOrder();
        String forM1 = newVehicle();
        String forM2 = newVehicle();
        available(order, forM1, forM2);
        putAnswers(new StateOutcome.Changed(order.from()));

        List<ResponseEntity<Map>> responses = atOnce(
                () -> pickup(order, "m-1", forM1),
                () -> pickup(order, "m-2", forM2));

        // El UPDATE ... WHERE status = 'OPEN' cambia una fila una sola vez: el otro operario recibe 403.
        assertThat(statuses(responses)).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.FORBIDDEN);
        String winner = (String) detail(order).get("operatorId");
        String loserVehicle = winner.equals("m-1") ? forM2 : forM1;
        // El perdedor no llegó a tocar su bici.
        verify(availability, never()).get(loserVehicle);
        assertThat(detail(order)).containsEntry("vehicleIds", List.of(winner.equals("m-1") ? forM1 : forM2));
    }

    @Test
    void twoOrdersClaimTheSameVehicleAtOnceAndTheUniqueIndexLetsOnlyOne() {
        String origin = newZone();
        Order first = newOrder(origin);
        Order second = newOrder(origin);
        String shared = newVehicle();
        // Los dos GET ven la bici AVAILABLE, y ninguno sigue hasta que ha llegado el otro.
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        when(availability.get(shared)).thenAnswer(invocation -> {
            bothChecked.await(5, TimeUnit.SECONDS);
            return new VehicleOutcome.Found(shared, "SCOOTER", origin, "AVAILABLE");
        });
        putAnswers(new StateOutcome.Changed(origin));

        List<ResponseEntity<Map>> responses = atOnce(
                () -> pickup(first, "m-1", shared),
                () -> pickup(second, "m-2", shared));

        // El INSERT ... ON CONFLICT DO NOTHING de la segunda choca con el índice único parcial: 0 filas, 409.
        assertThat(statuses(responses)).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        verify(availability, times(1)).changeState(eq(shared), eq("WITHDRAWN"), any(), anyString());
        List<Object> owners = new ArrayList<>();
        for (Order order : List.of(first, second)) {
            if (((List) detail(order).get("vehicleIds")).contains(shared)) {
                owners.add(order.id());
            }
        }
        assertThat(owners).hasSize(1);
    }

    @Test
    void twoCompletesOfTheSameOperatorAtOnceEmitOnlyOneEvent() {
        Order order = newOrder();
        String vehicle = newVehicle();
        available(order, vehicle);
        putAnswers(new StateOutcome.Changed(order.from()));
        assertThat(pickup(order, "m-1", vehicle).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Las dos peticiones entregan la bici y llegan juntas a la transacción final.
        CyclicBarrier bothDelivering = new CyclicBarrier(2);
        when(availability.get(vehicle)).thenAnswer(invocation -> {
            bothDelivering.await(5, TimeUnit.SECONDS);
            return new VehicleOutcome.Found(vehicle, "SCOOTER", order.from(), "WITHDRAWN");
        });

        List<ResponseEntity<Map>> responses = atOnce(
                () -> complete(order, "m-1"),
                () -> complete(order, "m-1"));

        // La segunda espera al bloqueo de fila, vuelve a evaluar el WHERE, ya no ve IN_PROGRESS y cambia 0 filas:
        // no desactiva las bicis ni publica nada, relee la orden COMPLETED del mismo operario y responde 200.
        assertThat(statuses(responses)).containsExactly(HttpStatus.OK, HttpStatus.OK);
        assertThat(responses).allSatisfy(r -> assertThat(r.getBody()).containsEntry("status", "COMPLETED"));
        assertThat(completedEvents(order)).hasSize(1);
    }

    // ---- Ayudas ----

    /** Lanza las dos peticiones en dos hilos y espera a las dos (como mucho 15 s). */
    @SafeVarargs
    private static List<ResponseEntity<Map>> atOnce(Callable<ResponseEntity<Map>>... calls) {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<Map>> call : calls) {
                futures.add(pool.submit(call));
            }
            List<ResponseEntity<Map>> responses = new ArrayList<>();
            for (Future<ResponseEntity<Map>> future : futures) {
                responses.add(future.get(15, TimeUnit.SECONDS));
            }
            return responses;
        } catch (Exception e) {
            throw new AssertionError("Las peticiones concurrentes no terminaron", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<HttpStatusCode> statuses(List<ResponseEntity<Map>> responses) {
        return responses.stream().map(ResponseEntity::getStatusCode).toList();
    }
}
