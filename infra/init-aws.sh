#!/bin/bash
echo "Inicializando recursos de AWS en LocalStack para UrbanMove..."

# Crear bucket S3 para el Data Lake
awslocal s3 mb s3://urbanmove-datalake

# Crear tópico SNS para el bus de eventos
awslocal sns create-topic --name urbanmove-events

# Crear Dead-Letter Queue (DLQ) genérica
awslocal sqs create-queue --queue-name urbanmove-dlq

echo "LocalStack inicializado correctamente."