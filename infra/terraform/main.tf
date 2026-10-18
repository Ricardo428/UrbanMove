############################
# Bus de eventos: SNS + SQS
############################

resource "aws_sns_topic" "event_bus" {
  name = "${var.name_prefix}-events"
}

resource "aws_sqs_queue" "dlq" {
  for_each = var.consumers
  name     = "${var.name_prefix}-${each.key}-dlq"
}

resource "aws_sqs_queue" "queue" {
  for_each = var.consumers
  name     = "${var.name_prefix}-${each.key}"

  # Tras max_receive_count intentos fallidos, el mensaje pasa a la DLQ.
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = var.max_receive_count
  })
}

# En AWS real, SNS solo puede escribir en una cola si la cola lo permite.
# LocalStack no lo exige, pero sin esta política los eventos no llegarían en AWS.
resource "aws_sqs_queue_policy" "allow_sns" {
  for_each  = var.consumers
  queue_url = aws_sqs_queue.queue[each.key].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowSnsToSendMessages"
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.queue[each.key].arn
      Condition = {
        ArnEquals = { "aws:SourceArn" = aws_sns_topic.event_bus.arn }
      }
    }]
  })
}

resource "aws_sns_topic_subscription" "subscription" {
  for_each = var.consumers

  topic_arn = aws_sns_topic.event_bus.arn
  protocol  = "sqs"
  endpoint  = aws_sqs_queue.queue[each.key].arn

  # El cuerpo del mensaje es el JSON del evento, sin el sobre de SNS.
  raw_message_delivery = true

  depends_on = [aws_sqs_queue_policy.allow_sns]
}

############################
# Data Lake: S3
############################

# Los nombres de bucket son únicos en todo AWS. En local se usa un nombre fijo;
# en AWS se añade el id de la cuenta para no chocar con el de otra persona.
data "aws_caller_identity" "current" {
  count = var.use_localstack ? 0 : 1
}

locals {
  datalake_bucket_name = var.use_localstack ? "${var.name_prefix}-datalake" : "${var.name_prefix}-datalake-${data.aws_caller_identity.current[0].account_id}"
}

resource "aws_s3_bucket" "data_lake" {
  bucket = local.datalake_bucket_name
}

resource "aws_s3_bucket_public_access_block" "data_lake" {
  bucket = aws_s3_bucket.data_lake.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_object" "layers" {
  for_each = toset(["raw/", "trusted/", "curated/"])
  bucket   = aws_s3_bucket.data_lake.id
  key      = each.key
}
