package br.teste.deroyque.clients;

import br.teste.deroyque.config.ProviderProperties;
import br.teste.deroyque.services.ProviderGateway.ActivationCall;
import br.teste.deroyque.services.ProviderResult.PermanentFailure;
import br.teste.deroyque.services.ProviderResult.Success;
import br.teste.deroyque.services.ProviderResult.TransientFailure;
import br.teste.deroyque.services.ProviderResult;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class HttpProviderGatewayTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final String PATH = "/provider/v1/activate";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private HttpProviderGateway gateway;
    private final ActivationCall call = new ActivationCall(
            UUID.fromString("0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90"), "CUST-1", "INTERNET-500", "corr-123456");

    @BeforeEach
    void setUp() {
        var properties = new ProviderProperties(URI.create(wireMock.baseUrl()),
                Duration.ofMillis(500), Duration.ofMillis(300), "secret-key");
        gateway = new HttpProviderGateway(RestClient.builder(), properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void successReturnsSuccessAndSendsIdempotencyHeaders() {
        wireMock.stubFor(post(PATH).willReturn(aResponse().withStatus(200)));

        ProviderResult result = gateway.activate(call);

        assertThat(result).isInstanceOf(Success.class);
        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Idempotency-Key", equalTo(call.requestId().toString()))
                .withHeader("X-Correlation-Id", equalTo("corr-123456"))
                .withHeader("X-Api-Key", equalTo("secret-key"))
                .withRequestBody(equalToJson("""
                        {"requestId":"0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90","customerId":"CUST-1","productCode":"INTERNET-500"}
                        """)));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422})
    void clientErrorsArePermanent(int status) {
        wireMock.stubFor(post(PATH).willReturn(aResponse().withStatus(status)));

        assertThat(gateway.activate(call))
                .isEqualTo(new PermanentFailure("Provider responded HTTP " + status));
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 500, 502, 504})
    void serverErrorsAreTransient(int status) {
        wireMock.stubFor(post(PATH).willReturn(aResponse().withStatus(status)));

        assertThat(gateway.activate(call))
                .isEqualTo(new TransientFailure("Provider responded HTTP " + status, null));
    }

    @Test
    void serviceUnavailableWithRetryAfterSecondsIsTransientWithDelay() {
        wireMock.stubFor(post(PATH).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "30")));

        assertThat(gateway.activate(call))
                .isEqualTo(new TransientFailure("Provider responded HTTP 503", Duration.ofSeconds(30)));
    }

    @Test
    void tooManyRequestsWithRetryAfterDateIsTransientWithDelay() {
        wireMock.stubFor(post(PATH).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "Mon, 26 Oct 2026 14:01:00 GMT")));

        assertThat(gateway.activate(call))
                .isEqualTo(new TransientFailure("Provider responded HTTP 429", Duration.ofSeconds(60)));
    }

    @Test
    void invalidRetryAfterIsIgnored() {
        assertThat(gateway.retryAfter("soon")).isNull();
        assertThat(gateway.retryAfter("-5")).isNull();
        assertThat(gateway.retryAfter(null)).isNull();
    }

    @Test
    void readTimeoutIsTransient() {
        wireMock.stubFor(post(PATH).willReturn(aResponse().withStatus(200).withFixedDelay(1_000)));

        ProviderResult result = gateway.activate(call);

        assertThat(result).isInstanceOf(TransientFailure.class);
        assertThat(((TransientFailure) result).reason()).startsWith("Provider unreachable");
    }

    @Test
    void connectionRefusedIsTransient() {
        var properties = new ProviderProperties(URI.create("http://localhost:1"),
                Duration.ofMillis(300), Duration.ofMillis(300), null);
        var unreachable = new HttpProviderGateway(RestClient.builder(), properties, Clock.systemUTC());

        assertThat(unreachable.activate(call)).isInstanceOf(TransientFailure.class);
    }
}
