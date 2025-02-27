package com.ssafy.common.redis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.ssafy.common.redis.service.SessionExpiredEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import javax.annotation.PostConstruct;
import java.util.Objects;

import static org.kurento.jsonrpc.client.JsonRpcClient.log;

@Configuration
public class RedisConfig {
    @Value("${spring.redis.host}")
    private String redisHost;

    @Value("${spring.redis.port}")
    private int redisPort;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // Redis 연결 설정
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration(redisHost, redisPort);

        // Lettuce 클라이언트 설정
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisConfig);

        connectionFactory.afterPropertiesSet(); // 연결 초기화
        return connectionFactory;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        // Redis 템플릿 설정
        RedisTemplate<String, Object> redistemplate = new RedisTemplate<>();
        redistemplate.setConnectionFactory(redisConnectionFactory());

        // Jackson Serializer 설정
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL
        );

        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(Object.class);
        serializer.setObjectMapper(objectMapper);

        redistemplate.setValueSerializer(serializer);
        redistemplate.setKeySerializer(new StringRedisSerializer());
        redistemplate.afterPropertiesSet();

        return redistemplate;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            ApplicationEventPublisher eventPublisher) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        container.addMessageListener((message, pattern) -> {
            String expiredKey = new String(message.getBody());
            log.info("__keyevent@*__:expired: {}", expiredKey);
            eventPublisher.publishEvent(new SessionExpiredEvent(this, expiredKey)); // 이벤트 발행
        }, new PatternTopic("__keyevent@*__:expired"));

        return container;
    }

    @PostConstruct
    public void enableRedisKeyspaceEvents() {
        RedisConnection connection = Objects.requireNonNull(redisConnectionFactory()).getConnection();
        connection.serverCommands().setConfig("notify-keyspace-events", "Ex");
        connection.close();
        log.info("Redis Keyspace Events enabled with 'Ex' configuration");
    }

}