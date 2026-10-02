package com.corundumstudio.socketio.spring.boot;

import com.corundumstudio.socketio.store.RedisTemplateStoreFactory;
import com.corundumstudio.socketio.store.StoreFactory;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/** 使用 Spring 管理的 Redis 模板和监听容器，支持跨节点房间发送。 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@AutoConfigureBefore(SocketioServerAutoConfiguration.class)
@ConditionalOnClass({RedisTemplate.class, RedisMessageListenerContainer.class})
@ConditionalOnProperty(prefix = SocketioRedisTemplateProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SocketioRedisTemplateProperties.class)
public class SocketioRedisTemplateConfiguration {
    @Bean
    public RedisTemplate<Object, Object> socketIoRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<Object, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper mapper = new ObjectMapper();
        // 使用字段避免把 Packet 的计算属性当作可反序列化的数据；保留 UUID 等 final 类型。
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.enableDefaultTyping(ObjectMapper.DefaultTyping.EVERYTHING);
        serializer.setObjectMapper(mapper);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisMessageListenerContainer socketIoRedisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTopicSerializer(RedisSerializer.string());
        return container;
    }

    @Bean(destroyMethod = "")
    public StoreFactory clientStoreFactory(
            @Qualifier("socketIoRedisTemplate") RedisTemplate<Object, Object> template,
            @Qualifier("socketIoRedisMessageListenerContainer") RedisMessageListenerContainer container) {
        return new RedisTemplateStoreFactory(template, container);
    }
}
