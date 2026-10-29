package br.teste.deroyque.controllers;

import br.teste.deroyque.config.SecurityConfig;
import br.teste.deroyque.dtos.CreateActivationCommand;
import br.teste.deroyque.dtos.CreationResult;
import br.teste.deroyque.exceptions.ActivationRequestNotFoundException;
import br.teste.deroyque.exceptions.DuplicateExternalIdException;
import br.teste.deroyque.models.ActivationRequest;
import br.teste.deroyque.services.ActivationRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivationRequestController.class)
@Import(SecurityConfig.class)
class ActivationRequestControllerTest {

    private static final String VALID_BODY = """
            {"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActivationRequestService service;

    private static JwtRequestPostProcessor writer() {
        return jwt().jwt(token -> token.subject("erp"))
                .authorities(new SimpleGrantedAuthority(SecurityConfig.SCOPE_WRITE));
    }

    private static JwtRequestPostProcessor reader() {
        return jwt().jwt(token -> token.subject("erp"))
                .authorities(new SimpleGrantedAuthority(SecurityConfig.SCOPE_READ));
    }

    private static ActivationRequest sample() {
        return ActivationRequest.create("EXT-12345", "CUST-10001", "INTERNET-500", "erp", "corr-123456",
                Instant.parse("2026-10-26T14:03:11Z"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        ActivationRequest request = sample();
        when(service.create(any())).thenReturn(new CreationResult(request, true));

        mockMvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/requests/" + request.getId()))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.id").value(request.getId().toString()))
                .andExpect(jsonPath("$.externalId").value("EXT-12345"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-26T14:03:11Z"));
    }

    @Test
    void createUsesTokenSubjectAsOwnerAndPropagatesCorrelationId() throws Exception {
        when(service.create(any())).thenReturn(new CreationResult(sample(), true));

        mockMvc.perform(post("/api/v1/requests").with(writer())
                        .header("X-Correlation-Id", "abc-123-correlation")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-Id", "abc-123-correlation"));

        verify(service).create(new CreateActivationCommand(
                "EXT-12345", "CUST-10001", "INTERNET-500", "erp", "abc-123-correlation"));
    }

    @Test
    void repeatedIdenticalRequestReturns200WithExistingRecord() throws Exception {
        ActivationRequest existing = sample();
        when(service.create(any())).thenReturn(new CreationResult(existing, false));

        mockMvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").value(existing.getId().toString()));
    }

    @Test
    void sameExternalIdWithDifferentContentReturns409() throws Exception {
        when(service.create(any())).thenThrow(new DuplicateExternalIdException("EXT-12345"));

        mockMvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Solicitação duplicada"))
                .andExpect(jsonPath("$.detail", containsString("EXT-12345")));
    }

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"EXT 1","productCode":"%s"}
                                """.formatted("X".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.deroyque.example/problems/validation-error"))
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.instance").value("/api/v1/requests"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[?(@.field == 'customerId')].message").value(hasItem("é obrigatório")))
                .andExpect(jsonPath("$.errors[?(@.field == 'externalId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'productCode')]").exists());

        verify(service, never()).create(any());
    }

    @Test
    void malformedJsonReturns400WithoutInternalDetails() throws Exception {
        mockMvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalId\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("jackson"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void oversizedBodyReturns413() throws Exception {
        mockMvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"x\":\"" + "a".repeat(20_000) + "\"}"))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    void getReturnsRequest() throws Exception {
        ActivationRequest request = sample();
        when(service.get(request.getId(), "erp")).thenReturn(request);

        mockMvc.perform(get("/api/v1/requests/{id}", request.getId()).with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(request.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getUnknownReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(eq(id), any())).thenThrow(new ActivationRequestNotFoundException(id));

        mockMvc.perform(get("/api/v1/requests/{id}", id).with(reader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso não encontrado"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void getWithInvalidIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/requests/{id}", "not-a-uuid").with(reader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedErrorReturns500WithoutStackTrace() throws Exception {
        when(service.get(any(), any())).thenThrow(new IllegalStateException("db password=secret leaked"));

        mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(reader()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Erro interno"))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void searchRejectsPageSizeAboveLimit() throws Exception {
        mockMvc.perform(get("/api/v1/requests").param("size", "1000").with(reader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void withoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.title").value("Não autenticado"));

        mockMvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readScopeCannotCreate() throws Exception {
        mockMvc.perform(post("/api/v1/requests").with(reader())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acesso negado"));

        verify(service, never()).create(any());
    }

    @Test
    void writeScopeCannotRead() throws Exception {
        mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(writer()))
                .andExpect(status().isForbidden());
    }
}
