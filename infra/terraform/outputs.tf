output "event_bus_topic_arn" {
  description = "ARN del topic SNS por el que viajan todos los eventos."
  value       = aws_sns_topic.event_bus.arn
}

output "queue_urls" {
  description = "URL de la cola de cada consumidor."
  value       = { for name, q in aws_sqs_queue.queue : name => q.url }
}

output "dlq_urls" {
  description = "URL de la dead-letter queue de cada consumidor."
  value       = { for name, q in aws_sqs_queue.dlq : name => q.url }
}

output "datalake_bucket" {
  description = "Nombre del bucket S3 del Data Lake."
  value       = aws_s3_bucket.data_lake.bucket
}
