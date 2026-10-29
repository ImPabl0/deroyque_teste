package br.teste.deroyque.clients;

import br.teste.deroyque.config.ProviderProperties;
import br.teste.deroyque.services.ProviderGateway;
import br.teste.deroyque.services.ProviderResult.PermanentFailure;
import br.teste.deroyque.services.ProviderResult.Success;
import br.teste.deroyque.services.ProviderResult.TransientFailure;
import br.teste.deroyque.services.ProviderResult;
import br.teste.deroyque.utils.CorrelationId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Cliente HTTP do provedor. Traduz cada resposta em um {@link ProviderResult}:
 * <ul>
 *     <li>2xx → sucesso;</li>
 *     <li>408, 429, 5xx, timeout e erro de conexão → falha temporária (com Retry-After, se houver);</li>
 *     <li>demais 4xx → falha definitiva (a requisição é inválida para o provedor).</li>
 * </ul>
 * O id da solicitação vai como {@code Idempotency-Key}: após um timeout (resultado ambíguo) a nova tentativa
 * usa a mesma chave, e o provedor pode reconhecer que já ativou o produto.
 */
@Component
public class HttpProviderGateway implements ProviderGateway {

    static final String ACTIVATE_PATH = "/provider/v1/activate";

    private final RestClient restClient;
    private final Clock clock;

    public HttpProviderGateway(RestClient.Builder builder, ProviderProperties properties, Clock clock) {
        // HTTP/1.1 explícito: evita a tentativa de upgrade h2c, que alguns servidores/proxies rejeitam.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient.Builder configured = builder.clone()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory);
        if (StringUtils.hasText(properties.apiKey())) {
            configured.defaultHeader("X-Api-Key", properties.apiKey());
        }
        this.restClient = configured.build();
        this.clock = clock;
    }

    @Override
    public ProviderResult activate(ActivationCall call) {
        try {
            return restClient.post()
                    .uri(ACTIVATE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", call.requestId().toString())
                    .header(CorrelationId.HEADER, call.correlationId())
                    .body(new ActivationPayload(call.requestId(), call.customerId(), call.productCode()))
                    .exchange((request, response) -> classify(response.getStatusCode(), response.getHeaders()), true);
        } catch (ResourceAccessException e) {
            // Timeout de conexão/leitura ou provedor inacessível. No timeout de leitura o provedor pode ter
            // processado: a nova tentativa é segura por causa da chave de idempotência.
            return new TransientFailure("Provider unreachable: " + e.getMostSpecificCause().getClass().getSimpleName(), null);
        }
    }

    private ProviderResult classify(HttpStatusCode status, HttpHeaders headers) {
        if (status.is2xxSuccessful()) {
            return new Success();
        }
        String reason = "Provider responded HTTP " + status.value();
        if (status.is5xxServerError()
                || status.value() == HttpStatus.TOO_MANY_REQUESTS.value()
                || status.value() == HttpStatus.REQUEST_TIMEOUT.value()) {
            return new TransientFailure(reason, retryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)));
        }
        return new PermanentFailure(reason);
    }

    /** Retry-After em segundos ou como data HTTP (RFC 9110). Valor inválido é ignorado. */
    Duration retryAfter(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notSeconds) {
            try {
                ZonedDateTime date = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration until = Duration.between(clock.instant(), date.toInstant());
                return until.isNegative() ? Duration.ZERO : until;
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    record ActivationPayload(UUID requestId, String customerId, String productCode) {
    }
}
