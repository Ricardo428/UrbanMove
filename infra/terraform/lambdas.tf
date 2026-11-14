############################
# Lambda de expiración de reservas
############################

# Solo se crea con deploy_expiration_lambda = true: necesita el jar ya compilado
# (./mvnw package -pl lambdas/expiration -am) y el secreto JWT.
locals {
  deploy_expiration = var.deploy_expiration_lambda ? 1 : 0

  expiration_jar = coalesce(
    var.expiration_lambda_jar,
    "${path.module}/../../lambdas/expiration/target/expiration-lambda.jar",
  )
}

# En AWS Academy no se pueden crear roles: se usa el del laboratorio (LabRole).
# LocalStack no comprueba el rol, así que le basta un ARN con el formato correcto.
data "aws_iam_role" "lab" {
  count = var.deploy_expiration_lambda && !var.use_localstack ? 1 : 0
  name  = var.lambda_role_name
}

locals {
  lambda_role_arn = var.use_localstack ? "arn:aws:iam::000000000000:role/lambda-role" : try(data.aws_iam_role.lab[0].arn, null)
}

resource "aws_lambda_function" "expiration" {
  count = local.deploy_expiration

  function_name    = "${var.name_prefix}-expiration"
  role             = local.lambda_role_arn
  runtime          = "java21"
  handler          = "es.ulpgc.urbanmove.lambdas.expiration.ExpirationHandler::handleRequest"
  filename         = local.expiration_jar
  source_code_hash = filebase64sha256(local.expiration_jar)
  timeout          = 60
  memory_size      = 512

  environment {
    variables = {
      RESERVATIONS_URL     = var.reservations_url
      URBANMOVE_JWT_SECRET = var.jwt_secret
    }
  }

  lifecycle {
    precondition {
      condition     = var.jwt_secret != null && length(var.jwt_secret) >= 32
      error_message = "Define jwt_secret con el mismo valor que JWT_SECRET (al menos 32 caracteres): -var jwt_secret=... o TF_VAR_jwt_secret."
    }
  }
}

# EventBridge dispara la Lambda cada minuto.
resource "aws_cloudwatch_event_rule" "expiration_schedule" {
  count = local.deploy_expiration

  name                = "${var.name_prefix}-expiration-schedule"
  description         = "Ejecuta la Lambda de expiración de reservas cada minuto."
  schedule_expression = "rate(1 minute)"
}

resource "aws_cloudwatch_event_target" "expiration" {
  count = local.deploy_expiration

  rule = aws_cloudwatch_event_rule.expiration_schedule[0].name
  arn  = aws_lambda_function.expiration[0].arn
}

resource "aws_lambda_permission" "allow_eventbridge" {
  count = local.deploy_expiration

  statement_id  = "AllowExecutionFromEventBridge"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.expiration[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.expiration_schedule[0].arn
}

# EventBridge invoca la Lambda de forma asíncrona y, por defecto, Lambda reintenta dos veces una invocación
# fallida. Aquí sobran: la siguiente ejecución (un minuto después) vuelve a encontrar las reservas que
# fallaron, y los reintentos solo repetirían las mismas llamadas y el mismo error en los logs.
resource "aws_lambda_function_event_invoke_config" "expiration" {
  count = local.deploy_expiration

  function_name          = aws_lambda_function.expiration[0].function_name
  maximum_retry_attempts = 0
}
