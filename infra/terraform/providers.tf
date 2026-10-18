# Con use_localstack = true el proveedor apunta a LocalStack (credenciales ficticias y sin
# validaciones contra AWS). Con false usa el AWS real y toma las credenciales del entorno
# (en AWS Academy: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY y AWS_SESSION_TOKEN).
provider "aws" {
  region = var.aws_region

  access_key = var.use_localstack ? "test" : null
  secret_key = var.use_localstack ? "test" : null

  skip_credentials_validation = var.use_localstack
  skip_requesting_account_id  = var.use_localstack
  skip_metadata_api_check     = var.use_localstack
  s3_use_path_style           = var.use_localstack

  endpoints {
    s3  = var.use_localstack ? var.localstack_endpoint : null
    sns = var.use_localstack ? var.localstack_endpoint : null
    sqs = var.use_localstack ? var.localstack_endpoint : null
  }
}
