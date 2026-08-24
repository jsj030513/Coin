package com.coin.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialEncryptionServiceTest {
    @Test
    void encryptsWithRandomIvAndDecrypts() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        CredentialEncryptionService service = new CredentialEncryptionService(key);

        String first = service.encrypt("private-secret");
        String second = service.encrypt("private-secret");

        assertThat(first).isNotEqualTo(second).doesNotContain("private-secret");
        assertThat(service.decrypt(first)).isEqualTo("private-secret");
        assertThat(service.decrypt(second)).isEqualTo("private-secret");
    }
}
