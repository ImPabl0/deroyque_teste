package br.teste.deroyque.advices;

import br.teste.deroyque.exceptions.ActivationRequestNotFoundException;
import br.teste.deroyque.exceptions.DuplicateExternalIdException;
import br.teste.deroyque.responses.ProblemTypes;
import br.teste.deroyque.utils.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Converte exceções em Problem Details (RFC 9457) com {@code traceId} para localizar o erro no log.
 * Mensagens internas e stack traces nunca vão para o consumidor.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ActivationRequestNotFoundException.class)
    ResponseEntity<Object> handleNotFound(ActivationRequestNotFoundException ex, WebRequest request) {
        return problem(ex, HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Recurso não encontrado",
                "Solicitação não encontrada.", request);
    }

    @ExceptionHandler(DuplicateExternalIdException.class)
    ResponseEntity<Object> handleDuplicate(DuplicateExternalIdException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.DUPLICATE_REQUEST, "Solicitação duplicada",
                "Já existe uma solicitação com o externalId '%s' e conteúdo diferente.".formatted(ex.getExternalId()),
                request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Object> handleConcurrentUpdate(ObjectOptimisticLockingFailureException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.CONCURRENT_UPDATE, "Atualização concorrente",
                "O recurso foi alterado por outra operação. Tente novamente.", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex, WebRequest request) {
        return problem(ex, HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR, "Dados inválidos",
                "Valor inválido para o parâmetro '%s'.".formatted(ex.getName()), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Não autenticado",
                "Token de acesso ausente, inválido ou expirado.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return problem(ex, HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Acesso negado",
                "O token não possui o escopo necessário para esta operação.", request);
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Object> handleIntegration(RestClientException ex, WebRequest request) {
        log.error("Integration error", ex);
        return problem(ex, HttpStatus.BAD_GATEWAY, ProblemTypes.INTEGRATION_ERROR, "Erro de integração",
                "Falha ao comunicar com um serviço externo.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error", ex);
        return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR, "Erro interno",
                "Ocorreu um erro inesperado. Informe o traceId ao suporte.", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", message(error)))
                .toList();
        ProblemDetail body = validationProblem("Um ou mais campos não passaram na validação.");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field", String.valueOf(result.getMethodParameter().getParameterName()),
                                "message", String.valueOf(error.getDefaultMessage()))))
                .toList();
        ProblemDetail body = validationProblem("Um ou mais parâmetros não passaram na validação.");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Ponto único por onde passam todas as respostas de erro: acrescenta o traceId. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            problem.setProperty("traceId", CorrelationId.current());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> problem(Exception ex, HttpStatus status, URI type, String title, String detail,
                                           WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(type);
        body.setTitle(title);
        return handleExceptionInternal(ex, body, new HttpHeaders(), status, request);
    }

    private static ProblemDetail validationProblem(String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        body.setType(ProblemTypes.VALIDATION_ERROR);
        body.setTitle("Dados inválidos");
        return body;
    }

    private static String message(FieldError error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "valor inválido";
    }
}
