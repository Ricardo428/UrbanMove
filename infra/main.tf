
locals {
  consumers = toset([
    "availability",
    "history",
    "datalake-ingest",
    "maintenance-orders",
    "relocation-orders"
  ])
}

resource "aws_sns_topic" "event_bus" {
  name = "urbanmove-events"
}

resource "aws_sqs_queue" "dlq" {
  for_each = local.consumers
  name     = "urbanmove-${each.key}-dlq"
}

resource "aws_sqs_queue" "queue" {
  for_each = local.consumers
  name     = "urbanmove-${each.key}"

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = 3
  })
}

resource "aws_sns_topic_subscription" "subscription" {
  for_each             = local.consumers
  topic_arn            = aws_sns_topic.event_bus.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.queue[each.key].arn
  raw_message_delivery = true
}

resource "aws_s3_bucket" "data_lake" {
  bucket = "urbanmove-datalake"
}


resource "aws_s3_object" "layers" {
  for_each = toset(["raw/", "trusted/", "curated/"])
  bucket   = aws_s3_bucket.data_lake.id
  key      = each.key
}