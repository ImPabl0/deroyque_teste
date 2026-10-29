package br.teste.deroyque.integration;

import br.teste.deroyque.config.SecurityConfig;
import br.teste.deroyque.models.ActivationRequest;
import br.teste.deroyque.models.enums.ActivationStatus;
import br.teste.deroyque.repositories.ActivationRequestRepository;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo completo: API → banco → dispatcher → provedor (WireMock) → status final.
 * Provedor configurado com timeout de leitura de 500 ms e no máximo 3 tentativas (application-test.properties).
 */
@SpringBootTest(properties = "activation.dispatcher.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivationFlowIntegrationTest {

    private static final String PROVIDER_PATH = "/provider/v1/activate";

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("provider.base-url", provider::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ActivationRequestRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAllInBatch();
    }

    @Test
    void successfulActivationEndsCompleted() throws Exception {
        stubProductResponse("INTERNET-500", aResponse().withStatus(200));

        UUID id = create("EXT-OK", "INTERNET-500", "flow-correlation-1");

        ActivationRequest done = awaitFinalStatus(id);
        assertThat(done.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(done.getAttempts()).isEqualTo(1);
        provider.verify(postRequestedFor(urlEqualTo(PROVIDER_PATH))
                .withHeader("Idempotency-Key", equalTo(id.toString()))
                .withHeader("X-Correlation-Id", equalTo("flow-correlation-1")));

        mockMvc.perform(get("/api/v1/requests/{id}", id).with(client("erp", SecurityConfig.SCOPE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void providerRejectionEndsFailedWithoutRetry() throws Exception {
        stubProductResponse("INVALID-PRODUCT", aResponse().withStatus(400));

        UUID id = create("EXT-400", "INVALID-PRODUCT", "flow-correlation-2");

        ActivationRequest done = awaitFinalStatus(id);
        assertThat(done.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(done.getAttempts()).isEqualTo(1);
        assertThat(done.getLastError()).isEqualTo("Provider responded HTTP 400");
    }

    @Test
    void serverErrorIsRetriedUntilSuccess() throws Exception {
        provider.stubFor(post(PROVIDER_PATH).inScenario("unstable").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("recovered"));
        provider.stubFor(post(PROVIDER_PATH).inScenario("unstable").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        UUID id = create("EXT-500", "INTERNET-500", "flow-correlation-3");

        ActivationRequest done = awaitFinalStatus(id);
        assertThat(done.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(done.getAttempts()).isEqualTo(2);
        // As duas chamadas usam a mesma chave de idempotência.
        provider.verify(2, postRequestedFor(urlEqualTo(PROVIDER_PATH))
                .withHeader("Idempotency-Key", equalTo(id.toString())));
    }

    @Test
    void timeoutIsRetriedAndFailsAfterMaxAttempts() throws Exception {
        stubProductResponse("SLOW-PRODUCT", aResponse().withStatus(200).withFixedDelay(1_500));

        UUID id = create("EXT-TIMEOUT", "SLOW-PRODUCT", "flow-correlation-4");

        ActivationRequest done = awaitFinalStatus(id);
        assertThat(done.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(done.getAttempts()).isEqualTo(3);
        assertThat(done.getLastError()).startsWith("Max attempts reached").contains("Provider unreachable");
    }

    @Test
    void clientCannotSeeRequestsFromAnotherClient() throws Exception {
        stubProductResponse("INTERNET-500", aResponse().withStatus(200));
        UUID id = create("EXT-BOLA", "INTERNET-500", "flow-correlation-5");

        mockMvc.perform(get("/api/v1/requests/{id}", id).with(client("other-system", SecurityConfig.SCOPE_READ)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/requests").with(client("other-system", SecurityConfig.SCOPE_READ)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listsWithFiltersAndPagination() throws Exception {
        stubProductResponse("INTERNET-500", aResponse().withStatus(200));
        stubProductResponse("INVALID-PRODUCT", aResponse().withStatus(400));
        UUID ok = create("EXT-LIST-1", "INTERNET-500", "flow-correlation-6");
        UUID failed = create("EXT-LIST-2", "INVALID-PRODUCT", "flow-correlation-7");
        awaitFinalStatus(ok);
        awaitFinalStatus(failed);

        mockMvc.perform(get("/api/v1/requests").param("status", "FAILED").param("size", "10")
                        .with(client("erp", SecurityConfig.SCOPE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(failed.toString()))
                .andExpect(jsonPath("$.size").value(10));

        mockMvc.perform(get("/api/v1/requests").param("customerId", "CUST-10001").param("size", "1")
                        .with(client("erp", SecurityConfig.SCOPE_READ)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    private UUID create(String externalId, String productCode, String correlationId) throws Exception {
        String body = """
                {"externalId":"%s","customerId":"CUST-10001","productCode":"%s"}
                """.formatted(externalId, productCode);
        String response = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/requests")
                        .with(client("erp", SecurityConfig.SCOPE_WRITE))
                        .header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private ActivationRequest awaitFinalStatus(UUID id) {
        return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> repository.findById(id).orElseThrow(), request -> request.getStatus().isFinal());
    }

    private static void stubProductResponse(String productCode,
                                            com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        provider.stubFor(post(PROVIDER_PATH)
                .withRequestBody(matchingJsonPath("$.productCode", equalTo(productCode)))
                .willReturn(response));
    }

    private static JwtRequestPostProcessor client(String clientId, String scope) {
        return jwt().jwt(token -> token.subject(clientId)).authorities(new SimpleGrantedAuthority(scope));
    }
}
