package es.ulpgc.urbanmove.messaging;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * Se activa sola en cualquier servicio con base de datos que dependa de "messaging".
 * Las credenciales de AWS se leen de la cadena por defecto del SDK (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
 * y AWS_SESSION_TOKEN en AWS Academy; valores ficticios en LocalStack), nunca de la configuración.
 */
@AutoConfiguration(after = {
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class})
@ConditionalOnBean({DataSource.class, PlatformTransactionManager.class})
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    /** Si una llamada a SNS o SQS se cuelga, se corta aquí y se reintenta más tarde. */
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    public EventPublisher eventPublisher(DataSource dataSource, MessagingProperties properties,
            @Value("${spring.application.name:unknown}") String applicationName) {
        String source = properties.source() == null || properties.source().isBlank()
                ? applicationName + "-service"
                : properties.source();
        return new EventPublisher(new JdbcTemplate(dataSource), source);
    }

    @Bean
    public OutboxRelay outboxRelay(DataSource dataSource, PlatformTransactionManager transactionManager,
            SnsClient sns, MessagingProperties properties) {
        return new OutboxRelay(new JdbcTemplate(dataSource), new TransactionTemplate(transactionManager), sns,
                properties);
    }

    @Bean
    public EventConsumer eventConsumer(DataSource dataSource, PlatformTransactionManager transactionManager,
            SqsClient sqs, MessagingProperties properties, List<EventHandler<?>> handlers) {
        return new EventConsumer(sqs, new JdbcTemplate(dataSource), new TransactionTemplate(transactionManager),
                properties, handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    public SnsClient snsClient(MessagingProperties properties) {
        SnsClientBuilder builder = SnsClient.builder()
                .region(Region.of(properties.region()))
                .overrideConfiguration(timeouts(API_CALL_TIMEOUT));
        if (hasEndpoint(properties)) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public SqsClient sqsClient(MessagingProperties properties) {
        // El long polling tiene que caber dentro del timeout de la llamada.
        Duration timeout = API_CALL_TIMEOUT.plus(properties.consumer().waitTime());
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                .overrideConfiguration(timeouts(timeout));
        if (hasEndpoint(properties)) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    private static ClientOverrideConfiguration timeouts(Duration apiCallTimeout) {
        return ClientOverrideConfiguration.builder().apiCallTimeout(apiCallTimeout).build();
    }

    private static boolean hasEndpoint(MessagingProperties properties) {
        return properties.endpoint() != null && !properties.endpoint().isBlank();
    }
}
