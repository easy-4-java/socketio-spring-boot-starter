package com.corundumstudio.socketio.spring.boot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.corundumstudio.socketio.AuthorizationListener;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.handler.SuccessAuthorizationListener;
import com.corundumstudio.socketio.listener.DefaultExceptionListener;
import com.corundumstudio.socketio.listener.ExceptionListener;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import com.corundumstudio.socketio.store.StoreFactory;

import io.netty.channel.epoll.Epoll;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = SocketioServerProperties.PREFIX, value = "enabled", havingValue = "true")
@EnableConfigurationProperties({ SocketioServerProperties.class })
public class SocketioServerAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public AuthorizationListener socketAuthzListener() {
		return new SuccessAuthorizationListener();
	}
	
	@Bean
	@ConditionalOnMissingBean
	public ExceptionListener exceptionListener() {
		return  new DefaultExceptionListener();
	}
	
	@Bean(destroyMethod = "")
	@ConditionalOnMissingBean
	public StoreFactory clientStoreFactory() {
		return new MemoryStoreFactory();
	}
	
	@Bean(destroyMethod = "")
	@ConditionalOnMissingBean
	public SocketIOServer socketIOServer(
			SocketioServerProperties config,
			AuthorizationListener socketAuthzListener,
			ExceptionListener exceptionListener, 
			StoreFactory clientStoreFactory) {

		// 身份验证
		config.setAuthorizationListener(socketAuthzListener);
		config.setExceptionListener(exceptionListener);
		config.setStoreFactory(clientStoreFactory);

		if (config.isUseLinuxNativeEpoll()
				&& !config.isFailIfNativeEpollLibNotPresent()
				&& !Epoll.isAvailable()) {
			log.warn("Epoll library not available, disabling native epoll");
			config.setUseLinuxNativeEpoll(false);
		}

		return new SocketIOServer(config);
	}

	@Bean
	public SocketioServerLifecycle socketioServerLifecycle(SocketIOServer server) {
		return new SocketioServerLifecycle(server);
	}

	@Bean
	public static SpringAnnotationScanner springAnnotationScanner(SocketIOServer socketServer) {
		return new SpringAnnotationScanner(socketServer);
	}
	
}
