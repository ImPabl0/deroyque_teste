package br.teste.deroyque.models;

import br.teste.deroyque.exceptions.InvalidStatusTransitionException;
import br.teste.deroyque.models.enums.ActivationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");

    private ActivationRequest newRequest() {
        return ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", "erp", "corr-123456", NOW);
    }

    @Test
    void startsPendingAndDueImmediately() {
        ActivationRequest request = newRequest();

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(request.getCreatedAt()).isEqualTo(NOW).isEqualTo(request.getUpdatedAt());
    }

    @Test
    void completesAfterProcessing() {
        ActivationRequest request = newRequest();
        Instant later = NOW.plusSeconds(1);

        request.startProcessing(NOW);
        request.complete(later);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getUpdatedAt()).isEqualTo(later);
    }

    @Test
    void transientFailureGoesBackToPendingAndCountsAttempts() {
        ActivationRequest request = newRequest();
        Instant retryAt = NOW.plusSeconds(10);

        request.startProcessing(NOW);
        request.scheduleRetry("HTTP 500", retryAt, NOW);
        request.startProcessing(retryAt);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(2);
        assertThat(request.getLastError()).isEqualTo("HTTP 500");
    }

    @Test
    void cannotCompleteWithoutProcessing() {
        ActivationRequest request = newRequest();

        assertThatThrownBy(() -> request.complete(NOW))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("PENDING -> COMPLETED");
    }

    @Test
    void completedDoesNotGoBackToProcessing() {
        ActivationRequest request = newRequest();
        request.startProcessing(NOW);
        request.complete(NOW);

        assertThatThrownBy(() -> request.startProcessing(NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> request.scheduleRetry("x", NOW, NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void failedIsFinal() {
        ActivationRequest request = newRequest();
        request.startProcessing(NOW);
        request.fail("HTTP 400", NOW);

        assertThat(request.getStatus().isFinal()).isTrue();
        assertThatThrownBy(() -> request.startProcessing(NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ActivationStatus.class, names = {"COMPLETED", "FAILED"})
    void finalStatusesAcceptNoTransition(ActivationStatus finalStatus) {
        for (ActivationStatus target : ActivationStatus.values()) {
            assertThat(finalStatus.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void errorMessageIsTruncatedToColumnSize() {
        ActivationRequest request = newRequest();
        request.startProcessing(NOW);

        request.fail("x".repeat(1000), NOW);

        assertThat(request.getLastError()).hasSize(255);
    }

    @Test
    void identifiesLegitimateRetry() {
        ActivationRequest request = newRequest();

        assertThat(request.isSameRequest("erp", "CUST-1", "INTERNET-500")).isTrue();
        assertThat(request.isSameRequest("erp", "CUST-1", "TV-100")).isFalse();
        assertThat(request.isSameRequest("other-system", "CUST-1", "INTERNET-500")).isFalse();
    }
}
