package com.jack.autocodebackend.model.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedSessionTest {

    @Test
    void storesOnlyPositiveUserIdAndNonReversibleFingerprint() {
        String encodedCredential = "{pbkdf2}encoded-credential";

        AuthenticatedSession session = AuthenticatedSession.fromCredential(
                5_000_000_000L, encodedCredential);

        assertThat(session.userId()).isEqualTo(5_000_000_000L);
        assertThat(session.credentialFingerprint()).hasSize(64)
                .doesNotContain(encodedCredential, "pbkdf2");
        assertThat(session.matchesCredential(encodedCredential)).isTrue();
        assertThat(session.matchesCredential(encodedCredential + "-changed")).isFalse();
        assertThat(session.matchesCredential(null)).isFalse();
    }

    @Test
    void rejectsInvalidState() {
        assertThatThrownBy(() -> AuthenticatedSession.fromCredential(0L, "credential"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedSession(1L, "not-a-fingerprint"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
