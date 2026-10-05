package io.github.spendice.linkshortener.application;

import io.github.spendice.linkshortener.application.port.AliasStore;
import io.github.spendice.linkshortener.application.port.AssignmentStore;
import io.github.spendice.linkshortener.domain.Alias;
import io.github.spendice.linkshortener.domain.AliasSequence;
import io.github.spendice.linkshortener.domain.Assignment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Fakes en memoria para probar los casos de uso sin HTTP ni base de datos. */
final class InMemoryStores {

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    static final class FakeAliasStore implements AliasStore {
        private final AliasSequence sequence = new AliasSequence(Set.of());
        private final Map<String, Alias> byCode = new HashMap<>();
        private long nextIndex = 0;

        @Override
        public Alias claimNewCode() {
            long index = sequence.indexOfNextCode(nextIndex);
            nextIndex = index + 1;
            Alias alias = new Alias(sequence.codeAt(index), null);
            byCode.put(alias.code(), alias);
            return alias;
        }

        @Override
        public Optional<Alias> findByCode(String code) {
            return Optional.ofNullable(byCode.get(code));
        }

        @Override
        public void assignCurrent(String code, long assignmentId) {
            byCode.put(code, new Alias(code, assignmentId));
        }
    }

    static final class FakeAssignmentStore implements AssignmentStore {
        private final Map<Long, Assignment> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

        @Override
        public Assignment save(Assignment assignment) {
            Assignment saved = new Assignment(ids.getAndIncrement(), assignment.aliasCode(),
                    assignment.destination(), assignment.createdAt(), assignment.expiresAt());
            byId.put(saved.id(), saved);
            return saved;
        }

        @Override
        public Optional<Assignment> findById(long id) {
            return Optional.ofNullable(byId.get(id));
        }

        long count() {
            return byId.size();
        }
    }
}
