variable "use_localstack" {
  description = "true para crear los recursos en LocalStack; false para hacerlo en AWS (por ejemplo, AWS Academy)."
  type        = bool
  default     = false
}

variable "localstack_endpoint" {
  description = "URL de LocalStack. Solo se usa cuando use_localstack es true."
  type        = string
  default     = "http://localhost:4566"
}

variable "aws_region" {
  description = "Región de AWS. AWS Academy suele limitarla a us-east-1 o us-west-2."
  type        = string
  default     = "us-east-1"
}

variable "name_prefix" {
  description = "Prefijo de los nombres del topic, las colas y el bucket."
  type        = string
  default     = "urbanmove"
}

variable "consumers" {
  description = "Servicios y lambdas que consumen eventos. Cada uno tiene su cola y su dead-letter queue (ver docs/events.md)."
  type        = set(string)
  default = [
    "availability",
    "history",
    "identity",
    "reservations",
    "datalake-ingest",
    "maintenance-orders",
    "relocation-orders",
  ]
}

variable "max_receive_count" {
  description = "Intentos fallidos antes de que un mensaje pase a la dead-letter queue."
  type        = number
  default     = 3
}

variable "deploy_expiration_lambda" {
  description = "true para crear la Lambda de expiración de reservas y su programación. Necesita el jar compilado y jwt_secret."
  type        = bool
  default     = false
}

variable "expiration_lambda_jar" {
  description = "Ruta del jar de la Lambda. Si es null se usa lambdas/expiration/target/expiration-lambda.jar."
  type        = string
  default     = null
}

variable "lambda_role_name" {
  description = "Rol de IAM que usan las Lambdas en AWS. En AWS Academy no se pueden crear roles y se usa LabRole."
  type        = string
  default     = "LabRole"
}

variable "reservations_url" {
  description = "URL desde la que la Lambda llama a reservations. El valor por defecto es el nombre del servicio en la red de Docker Compose."
  type        = string
  default     = "http://reservations:8084"
}

variable "jwt_secret" {
  description = "Secreto con el que la Lambda firma su token de servicio: el mismo que JWT_SECRET. Pásalo con -var o TF_VAR_jwt_secret; nunca en un fichero del repositorio."
  type        = string
  default     = null
  sensitive   = true
}
