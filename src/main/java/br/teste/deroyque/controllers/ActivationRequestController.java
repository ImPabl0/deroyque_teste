package br.teste.deroyque.controllers;

import br.teste.deroyque.dtos.ActivationRequestResponse;
import br.teste.deroyque.dtos.CreateActivationCommand;
import br.teste.deroyque.dtos.CreateActivationRequestBody;
import br.teste.deroyque.dtos.CreationResult;
import br.teste.deroyque.dtos.PageResponse;
import br.teste.deroyque.models.enums.ActivationStatus;
import br.teste.deroyque.security.ClientIdentity;
import br.teste.deroyque.services.ActivationRequestService;
import br.teste.deroyque.utils.CorrelationId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    /**
     * 201 quando a solicitação é criada; 200 com o registro existente quando é a repetição de uma
     * solicitação idêntica (retry do sistema de origem); 409 quando o externalId já existe com outro conteúdo.
     */
    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequestBody body,
                                                            @AuthenticationPrincipal Jwt jwt) {
        CreationResult result = service.create(new CreateActivationCommand(
                body.externalId(), body.customerId(), body.productCode(),
                ClientIdentity.of(jwt), CorrelationId.current()));

        ActivationRequestResponse response = ActivationRequestResponse.from(result.request());
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return result.created()
                ? ResponseEntity.created(location).body(response)
                : ResponseEntity.ok().location(location).body(response);
    }

    @GetMapping("/{id}")
    public ActivationRequestResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ActivationRequestResponse.from(service.get(id, ClientIdentity.of(jwt)));
    }

    @GetMapping
    public PageResponse<ActivationRequestResponse> search(
            @RequestParam(required = false) ActivationStatus status,
            @RequestParam(required = false) @Size(max = 64) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(
                service.search(ClientIdentity.of(jwt), status, customerId, pageable),
                ActivationRequestResponse::from);
    }
}
