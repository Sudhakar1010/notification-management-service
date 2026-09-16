package com.nms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class WebhookClientConfig {

    @Bean
    public RestClient webhookRestClient(
            @Value("${notification.delivery.webhook-connect-timeout-ms:2000}") long connectTimeoutMillis,
            @Value("${notification.delivery.webhook-read-timeout-ms:3000}") long readTimeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
