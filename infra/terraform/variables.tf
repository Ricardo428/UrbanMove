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
