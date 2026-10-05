package io.github.spendice.linkshortener.application;

import io.github.spendice.linkshortener.application.InMemoryStores.FakeAliasStore;
import io.github.spendice.linkshortener.application.InMemoryStores.FakeAssignmentStore;
import io.github.spendice.linkshortener.MutableClock;
import io.github.spendice.linkshortener.domain.Assignment;
import io.github.spendice.linkshortener.domain.DestinationValidator;
import io.github.spendice.linkshortener.domain.ExpirationPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResolveLinkTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final FakeAssignmentStore assignments = new FakeAssignmentStore();
    private final FakeAliasStore aliases = new FakeAliasStore(assignments);
    private final CreateLink createLink = new CreateLink(
            new DestinationValidator(Set.of()),
            new ExpirationPolicy(Duration.ofMinutes(60)),
            aliases,
            assignments,
            clock);
    private final ResolveLink resolveLink = new ResolveLink(aliases, assignments, clock);

    @Test
    void resuelveLaAsignacionVigente() {
        CreatedLink link = createLink.create("https://ejemplo.com/doc?a=1#frag");

        Optional<Assignment> resolved = resolveLink.resolve(link.aliasCode());

        assertThat(resolved).isPresent();
        assertThat(resolved.get().destination()).isEqualTo("https://ejemplo.com/doc?a=1#frag");
        assertThat(resolved.get().expiresAt()).isEqualTo(link.expiresAt());
    }

    @Test
    void noResuelveAlAlcanzarElVencimiento() {
        CreatedLink link = createLink.create("https://ejemplo.com/doc");

        clock.set(link.expiresAt().minusNanos(1));
        assertThat(resolveLink.resolve(link.aliasCode())).isPresent();

        clock.set(link.expiresAt());
        assertThat(resolveLink.resolve(link.aliasCode())).isEmpty();
    }

    @Test
    void noResuelveAliasDesconocido() {
        assertThat(resolveLink.resolve("9")).isEmpty();
    }

    @Test
    void noResuelveCodigosFueraDelAlfabeto() {
        assertThat(resolveLink.resolve("0")).isEmpty();
        assertThat(resolveLink.resolve("no-es-alias")).isEmpty();
    }
}
