package br.teste.deroyque.services;

import br.teste.deroyque.config.ActivationProperties;
import br.teste.deroyque.models.ActivationRequest;
import br.teste.deroyque.models.enums.ActivationStatus;
import br.teste.deroyque.repositories.ActivationRequestRepository;
import br.teste.deroyque.services.ProviderGateway.ActivationCall;
import br.teste.deroyque.services.ProviderResult.PermanentFailure;
import br.teste.deroyque.services.ProviderResult.Success;
import br.teste.deroyque.services.ProviderResult.TransientFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");

    @Mock
    private ActivationRequestRepository repository;
    @Mock
    private ProviderGateway provider;

    private ActivationProcessor processor;
    private ActivationRequest request;

    @BeforeEach
    void setUp() {
        var properties = new ActivationProperties(3, Duration.ofSeconds(2), Duration.ofSeconds(30), 10, Duration.ofMinutes(2));
        processor = new ActivationProcessor(repository,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                provider, new RetryPolicy(properties), Clock.fixed(NOW, ZoneOffset.UTC));
        request = ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", "erp", "corr-123456", NOW);
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
    }

    @Test
    void successCompletesRequest() {
        when(repository.saveAndFlush(request)).thenReturn(request);
        when(provider.activate(any())).thenReturn(new Success());

        processor.process(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(1);
    }

    @Test
    void sendsRequestIdAsIdempotencyKeyAndCorrelationId() {
        when(repository.saveAndFlush(request)).thenReturn(request);
        when(provider.activate(any())).thenReturn(new Success());

        processor.process(request.getId());

        var call = ArgumentCaptor.forClass(ActivationCall.class);
        verify(provider).activate(call.capture());
        assertThat(call.getValue().requestId()).isEqualTo(request.getId());
        assertThat(call.getValue().correlationId()).isEqualTo("corr-123456");
    }

    @Test
    void permanentFailureMarksFailedWithoutRetry() {
        when(repository.saveAndFlush(request)).thenReturn(request);
        when(provider.activate(any())).thenReturn(new PermanentFailure("Provider responded HTTP 400"));

        processor.process(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getLastError()).isEqualTo("Provider responded HTTP 400");
    }

    @Test
    void transientFailureSchedulesRetry() {
        when(repository.saveAndFlush(request)).thenReturn(request);
        when(provider.activate(any())).thenReturn(new TransientFailure("Provider responded HTTP 500", null));

        processor.process(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getNextAttemptAt()).isBetween(NOW.plusSeconds(1), NOW.plusSeconds(2));
    }

    @Test
    void transientFailureHonorsRetryAfter() {
        when(repository.saveAndFlush(request)).thenReturn(request);
        when(provider.activate(any())).thenReturn(new TransientFailure("HTTP 503", Duration.ofSeconds(120)));

        processor.process(request.getId());

        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void transientFailureAfterMaxAttemptsMarksFailed() {
        when(repository.saveAndFlush(request)).thenReturn(request);
        when(provider.activate(any())).thenReturn(new TransientFailure("Provider unreachable", null));

        for (int attempt = 0; attempt < 3; attempt++) {
            processor.process(request.getId());
        }

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(3);
        assertThat(request.getLastError()).startsWith("Max attempts reached");
    }

    @Test
    void doesNotCallProviderWhenAnotherWorkerClaimedFirst() {
        when(repository.saveAndFlush(request))
                .thenThrow(new ObjectOptimisticLockingFailureException(ActivationRequest.class, request.getId()));

        processor.process(request.getId());

        verify(provider, never()).activate(any());
    }

    @Test
    void doesNotProcessRequestThatIsNoLongerPending() {
        request.startProcessing(NOW);
        request.complete(NOW);

        processor.process(request.getId());

        verify(provider, never()).activate(any());
    }

    @Test
    void stuckRequestIsRescheduled() {
        request.startProcessing(NOW);

        processor.recoverStuck(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getLastError()).isEqualTo("Processing interrupted");
    }
}
