package com.nms.delivery.providers;

import com.nms.common.Channel;
import com.nms.common.FailureType;
import com.nms.delivery.ChannelProvider;
import com.nms.delivery.DeliveryContext;
import com.nms.delivery.ProviderResult;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Real webhook provider -- unlike Email/SMS, this makes an actual outbound
 * HTTP call and classifies the failure type from the real response, not a
 * string match. `recipientId` is interpreted as the target URL for this
 * channel (see ARCHITECTURE.md ADR-012); any http(s) URL works, including
 * WebhookSinkController's local endpoints for deterministic demos/tests.
 */
@Component
public class WebhookChannelProvider implements ChannelProvider {

    private final RestClient webhookRestClient;

    public WebhookChannelProvider(RestClient webhookRestClient) {
        this.webhookRestClient = webhookRestClient;
    }

    @Override
    public Channel supportedChannel() {
        return Channel.WEBHOOK;
    }

    @Override
    public ProviderResult send(DeliveryContext context) {
        URI uri;
        try {
            uri = URI.create(context.recipientId());
            if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                return ProviderResult.failure(FailureType.INVALID_RECIPIENT,
                        "recipientId is not a valid http(s) webhook URL: " + context.recipientId());
            }
        } catch (IllegalArgumentException e) {
            return ProviderResult.failure(FailureType.INVALID_RECIPIENT,
                    "recipientId is not a valid webhook URL: " + context.recipientId());
        }

        WebhookPayload payload = new WebhookPayload(context.notificationId(), context.subject(),
                context.message(), context.severity(), context.priority());

        try {
            var response = webhookRestClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return ProviderResult.success("Webhook responded HTTP " + response.getStatusCode().value());
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            return ProviderResult.failure(FailureType.AUTH_ERROR,
                    "Webhook endpoint rejected credentials: HTTP " + e.getStatusCode().value());
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Gone e) {
            return ProviderResult.failure(FailureType.INVALID_RECIPIENT,
                    "Webhook target does not exist: HTTP " + e.getStatusCode().value());
        } catch (HttpClientErrorException.TooManyRequests e) {
            return ProviderResult.failure(FailureType.RATE_LIMITED,
                    "Webhook endpoint rate limited the request: HTTP 429");
        } catch (HttpClientErrorException e) {
            return ProviderResult.failure(FailureType.PERMANENT_PROVIDER_REJECTION,
                    "Webhook endpoint rejected the request: HTTP " + e.getStatusCode().value());
        } catch (HttpServerErrorException e) {
            return ProviderResult.failure(FailureType.TRANSIENT_PROVIDER_ERROR,
                    "Webhook endpoint returned a server error: HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            String cause = e.getCause() != null ? e.getCause().getClass().getSimpleName() : "";
            if (cause.toLowerCase().contains("timeout")) {
                return ProviderResult.failure(FailureType.TIMEOUT, "Webhook call timed out: " + e.getMessage());
            }
            return ProviderResult.failure(FailureType.TRANSIENT_PROVIDER_ERROR, "Webhook call failed: " + e.getMessage());
        }
    }
}
