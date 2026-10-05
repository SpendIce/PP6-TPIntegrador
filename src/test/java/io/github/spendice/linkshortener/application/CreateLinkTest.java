package io.github.spendice.linkshortener.application;

import io.github.spendice.linkshortener.application.InMemoryStores.FakeAliasStore;
import io.github.spendice.linkshortener.application.InMemoryStores.FakeAssignmentStore;
import io.github.spendice.linkshortener.MutableClock;
import io.github.spendice.linkshortener.domain.DestinationValidator;
import io.github.spendice.linkshortener.domain.ExpirationPolicy;
import io.github.spendice.linkshortener.domain.InvalidDestinationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateLinkTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final FakeAliasStore aliases = new FakeAliasStore();
    private final FakeAssignmentStore assignments = new FakeAssignmentStore();
    private final CreateLink createLink = new CreateLink(
            new DestinationValidator(Set.of()),
            new ExpirationPolicy(Duration.ofMinutes(60)),
            aliases,
            assignments,
            clock);

    @Test
    void creaAsignacionConVencimientoA60Minutos() {
        CreatedLink link = createLink.create("https://ejemplo.com/doc");

        assertThat(link.createdAt()).isEqualTo(T0);
        assertThat(link.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(60)));
        assertThat(link.destination()).isEqualTo("https://ejemplo.com/doc");
    }

    @Test
    void elPrimerAliasEsDeUnCaracterDelAlfabeto() {
        CreatedLink link = createLink.create("https://ejemplo.com/doc");

        assertThat(link.aliasCode()).isEqualTo("1");
    }

    @Test
    void dosCreacionesDelMismoDestinoSonAsignacionesIndependientes() {
        CreatedLink first = createLink.create("https://ejemplo.com/doc");
        clock.set(T0.plusSeconds(30));
        CreatedLink second = createLink.create("https://ejemplo.com/doc");

        assertThat(second.aliasCode()).isNotEqualTo(first.aliasCode());
        assertThat(second.createdAt()).isAfter(first.createdAt());
        assertThat(second.expiresAt()).isAfter(first.expiresAt());
        assertThat(assignments.count()).isEqualTo(2);
    }

    @Test
    void unDestinoInvalidoNoConsumeCodigos() {
        assertThatThrownBy(() -> createLink.create("no es una url"))
                .isInstanceOf(InvalidDestinationException.class);

        assertThat(assignments.count()).isZero();
        assertThat(createLink.create("https://ejemplo.com/doc").aliasCode()).isEqualTo("1");
    }
}
