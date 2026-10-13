#!/bin/bash
# LocalStack ejecuta este script cuando está listo (/etc/localstack/init/ready.d).
# Crea el tópico de eventos, una cola por consumidor con su dead-letter queue (DLQ)
# y el bucket del Data Lake con sus tres capas (raw, trusted y curated).
set -euo pipefail

REGION="${AWS_DEFAULT_REGION:-us-east-1}"
TOPIC="urbanmove-events"
BUCKET="urbanmove-datalake"
MAX_RECEIVE=3

# Una cola por consumidor de eventos (ver docs/events.md).
CONSUMERS="availability history datalake-ingest maintenance-orders relocation-orders"

echo "[init-aws] región: ${REGION}"

TOPIC_ARN=$(awslocal sns create-topic --name "${TOPIC}" --region "${REGION}" \
  --query TopicArn --output text)
echo "[init-aws] tópico: ${TOPIC_ARN}"

queue_arn() {
  awslocal sqs get-queue-attributes --queue-url "$1" --attribute-names QueueArn \
    --region "${REGION}" --query Attributes.QueueArn --output text
}

for name in ${CONSUMERS}; do
  dlq_url=$(awslocal sqs create-queue --queue-name "urbanmove-${name}-dlq" \
    --region "${REGION}" --query QueueUrl --output text)
  dlq_arn=$(queue_arn "${dlq_url}")

  # Tras MAX_RECEIVE intentos fallidos, el mensaje pasa a la DLQ para revisarlo y reprocesarlo.
  attrs=$(printf '{"RedrivePolicy":"{\\"deadLetterTargetArn\\":\\"%s\\",\\"maxReceiveCount\\":\\"%s\\"}"}' \
    "${dlq_arn}" "${MAX_RECEIVE}")
  queue_url=$(awslocal sqs create-queue --queue-name "urbanmove-${name}" \
    --region "${REGION}" --attributes "${attrs}" --query QueueUrl --output text)
  q_arn=$(queue_arn "${queue_url}")

  # Entrega "raw": el cuerpo del mensaje es el JSON del evento, sin el sobre de SNS.
  # Los filtros por tipo de evento se añadirán en la fase 2.
  awslocal sns subscribe --topic-arn "${TOPIC_ARN}" --protocol sqs \
    --notification-endpoint "${q_arn}" --attributes RawMessageDelivery=true \
    --region "${REGION}" > /dev/null

  echo "[init-aws] cola: urbanmove-${name} (+ urbanmove-${name}-dlq)"
done

if ! awslocal s3api head-bucket --bucket "${BUCKET}" > /dev/null 2>&1; then
  awslocal s3 mb "s3://${BUCKET}" --region "${REGION}" > /dev/null
fi
for layer in raw trusted curated; do
  awslocal s3api put-object --bucket "${BUCKET}" --key "${layer}/" > /dev/null
done
echo "[init-aws] bucket: s3://${BUCKET} (raw/, trusted/, curated/)"

echo "[init-aws] listo"
