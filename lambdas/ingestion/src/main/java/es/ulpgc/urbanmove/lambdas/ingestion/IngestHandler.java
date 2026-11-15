package es.ulpgc.urbanmove.lambdas.ingestion;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.util.List;

/**
 * Punto de entrada de la Lambda. Lambda lee la cola {@code urbanmove-datalake-ingest} (event source mapping de
 * Terraform) y le entrega los mensajes en lotes de hasta 10.
 *
 * Responde con los mensajes que fallaron (respuesta parcial de lote, {@code ReportBatchItemFailures}): SQS borra
 * los que salieron bien y solo reentrega los fallidos. Sin esto, un solo mensaje malo haría repetir las 10
 * escrituras del lote cada vez.
 */
public class IngestHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    // Se crea una vez por contenedor y se reutiliza entre invocaciones: abrir el cliente de S3 es lo más lento.
    private static IngestJob shared;

    private final IngestJob job;

    /** Lo usa Lambda. */
    public IngestHandler() {
        this(sharedJob());
    }

    IngestHandler(IngestJob job) {
        this.job = job;
    }

    private static synchronized IngestJob sharedJob() {
        if (shared == null) {
            shared = new IngestJob(S3RawStore.fromEnvironment());
        }
        return shared;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<IngestJob.Message> messages = event.getRecords().stream()
                .map(record -> new IngestJob.Message(record.getMessageId(), record.getBody()))
                .toList();
        IngestJob.Result result = job.run(messages, message -> context.getLogger().log(message + "\n"));
        return new SQSBatchResponse(result.failedIds().stream()
                .map(SQSBatchResponse.BatchItemFailure::new)
                .toList());
    }
}
