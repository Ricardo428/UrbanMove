package es.ulpgc.urbanmove.lambdas.ingestion;

import java.net.URI;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;

/** Escribe los eventos en el bucket del Data Lake. */
final class S3RawStore implements IngestJob.RawStore {

    private final S3Client s3;
    private final String bucket;

    S3RawStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /**
     * Lee el bucket de DATALAKE_BUCKET. DATALAKE_ENDPOINT solo se define en local (LocalStack): en AWS se deja
     * vacío y el SDK usa el S3 de verdad. Región y credenciales las pone Lambda (AWS_REGION, rol de ejecución).
     */
    static S3RawStore fromEnvironment() {
        String bucket = System.getenv("DATALAKE_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno DATALAKE_BUCKET");
        }
        var builder = S3Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder());
        String endpoint = System.getenv("DATALAKE_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            // LocalStack no resuelve nombres como bucket.localstack: se pide por ruta (localstack:4566/bucket/clave).
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return new S3RawStore(builder.build(), bucket);
    }

    @Override
    public void put(String key, String json) {
        s3.putObject(request -> request.bucket(bucket).key(key).contentType("application/json"),
                RequestBody.fromString(json));
    }
}
