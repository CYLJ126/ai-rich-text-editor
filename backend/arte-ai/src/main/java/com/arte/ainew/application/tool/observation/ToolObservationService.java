package com.arte.ainew.application.tool.observation;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.tool.ToolObservation;
import com.arte.ainew.spi.persistence.ToolObservationStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * 本人空间查询用例；扫描有界，未知不是零，工具资格在读取后及返回前分别复核。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 14:40 ✾
 */
public final class ToolObservationService {

    private final ToolObservationStore store;
    private final AdmissionAuthorization access;
    private final Clock clock;

    private record Observed(ToolObservationStore.Scan scan, List<ToolObservation.Item> items, Instant at) {
    }

    public ToolObservationService(ToolObservationStore store, AdmissionAuthorization access, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.access = Objects.requireNonNull(access);
        this.clock = Objects.requireNonNull(clock);
    }

    public Mono<ToolObservation.Page> list(ToolObservation.Query query, String cursor, int limit, ExecutionContext context) {
        ContractChecks.range(limit, "page limit", 1, 20);
        return Mono.defer(() -> {
            var fingerprint = query.fingerprint(ExecutionOwners.from(context));
            var after = decode(cursor, fingerprint, query);
            return observe(query, after, ToolObservation.LIST_SCAN_LIMIT, context).map(value -> {
                var records = value.items().stream().limit(limit).toList();
                var candidates = value.scan().records();
                boolean overflow = value.items().size() > limit;
                var last = candidates.isEmpty() ? null : candidates.getLast();
                if (overflow) {
                    var lastId = records.getLast().invocationId();
                    last = candidates.stream().filter(c -> c.metadata().invocationId().equals(lastId)).findFirst().orElseThrow();
                }
                boolean exhausted = !overflow && !value.scan().more();
                return new ToolObservation.Page(records, exhausted || last == null ? null : encode(fingerprint, last), exhausted,
                        candidates.size(), ToolObservation.LIST_SCAN_LIMIT, value.scan().historicalIncomplete(), value.at(), query, "CURRENT_SUBJECT");
            });
        });
    }

    public Mono<ToolObservation.Statistics> statistics(ToolObservation.Query query, ExecutionContext context) {
        return observe(query, null, ToolObservation.STATISTICS_SCAN_LIMIT, context).map(value -> summarize(query, value));
    }

    private Mono<Observed> observe(ToolObservation.Query query, ToolObservationStore.Position after, int limit, ExecutionContext context) {
        return Mono.defer(() -> {
            var at = clock.instant();
            var remaining = Duration.between(at, context.deadline());
            if (remaining.isNegative() || remaining.isZero())
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
            return access.require(context, AdmissionAuthorization.READ).flatMap(current -> {
                var owner = ExecutionOwners.from(current);
                return store.scan(owner, query, after, limit).flatMap(scan -> Flux.fromIterable(scan.records()).buffer(20)
                        .concatMap(batch -> store.load(owner, batch)).flatMapIterable(entries -> entries)
                        .collectList().flatMap(entries -> readable(entries, current))
                        // 当前全局资格重新读取；不让“第一次读取获准”成为跨请求或跨空间的读取凭证。
                        .flatMap(entries -> access.requireFreshRead(current, AdmissionAuthorization.READ).flatMap(fresh -> readable(entries, fresh))
                                .flatMapMany(Flux::fromIterable).filter(entry -> matches(query, entry))
                                .map(entry -> item(entry, at)).collectList())
                        .map(items -> new Observed(scan, items, at)));
            }).timeout(remaining).onErrorMap(TimeoutException.class, ignored -> new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
        });
    }

    private Mono<List<ToolObservationStore.Entry>> readable(List<ToolObservationStore.Entry> entries, ExecutionContext context) {
        return access.readInvocations(entries.stream().map(ToolObservationStore.Entry::invocation).toList(), context).map(invocations -> {
            var allowed = new HashSet<String>();
            invocations.forEach(i -> allowed.add(i.request().context().executionId()));
            return entries.stream().filter(e -> allowed.contains(e.invocation().request().context().executionId())).toList();
        });
    }

    private static boolean matches(ToolObservation.Query q, ToolObservationStore.Entry e) {
        var m = e.candidate().metadata();
        return (q.toolId() == null || q.toolId().equals(m.toolId())) && (q.bindingId() == null || q.bindingId().equals(m.bindingId()))
                && (q.state() == null || q.state() == e.invocation().state());
    }

    private static ToolObservation.Item item(ToolObservationStore.Entry e, Instant observedAt) {
        var i = e.invocation();
        var m = e.candidate().metadata();
        Instant at = observedAt.isBefore(i.updatedAt()) ? i.updatedAt() : observedAt;
        Long firstDelay = e.attempts().stream().map(Attempt::createdAt).min(Comparator.naturalOrder()).map(t -> millis(i.acceptedAt(), t)).orElse(null);
        Long approval = e.approval() == null ? null : millis(e.approval().createdAt(), e.approval().decidedAt() == null ? at : e.approval().decidedAt());
        var attemptSpans = e.attempts().stream().map(a -> millis(a.createdAt(),
                a.state() == Attempt.State.CREATED || a.state() == Attempt.State.RUNNING ? at : a.updatedAt())).toList();
        Long span = attemptSpans.isEmpty() || attemptSpans.stream().anyMatch(Objects::isNull) ? null : attemptSpans.stream().mapToLong(Long::longValue).sum();
        return new ToolObservation.Item(m.invocationId(), i.request().context().traceId(), i.request().context().parentExecutionId(),
                i.request().context().parentExecutionId() == null ? "INDEPENDENT" : "CHAT_CHILD", m.toolId(), m.toolVersion(), m.bindingId(), m.title(), m.providerId(),
                i.state(), Long.toString(i.version()), i.acceptedAt(), i.updatedAt(), e.attempts().size(), Math.max(0, e.attempts().size() - 1),
                i.error() == null ? null : i.error().code(), m.billingMode(), cost(e),
                new ToolObservation.Timings(firstDelay, approval, span, i.state().terminal() ? millis(i.acceptedAt(), i.updatedAt()) : null,
                        millis(i.acceptedAt(), i.state().terminal() ? i.updatedAt() : at)), m.inputBytes(), null);
    }

    private static Long millis(Instant from, Instant until) {
        // 跨节点时钟逆序明确未知；不能补成已知零。这只是记录跨度，不冒充适配器实际执行时间。
        var value = Duration.between(from, until);
        return value.isNegative() ? null : value.toMillis();
    }

    private static ToolObservation.Cost cost(ToolObservationStore.Entry e) {
        if ("FREE".equals(e.candidate().metadata().billingMode()))
            return new ToolObservation.Cost("FREE", null, null, null, false);
        if (e.attempts().size() > 1) throw new IllegalStateException("Paid tool observation requires single attempt");
        if (e.reservations().isEmpty()) return new ToolObservation.Cost("NOT_RESERVED", null, null, null, true);
        var r = e.reservations().values().iterator().next();
        var charge = e.charges().get(r.attemptId());
        if (charge != null && !charge.amount().currency().equals(r.reserved().currency()))
            throw new IllegalStateException("Tool charge currency mismatch");
        var currency = r.reserved().currency().getCurrencyCode();
        var settlement = e.settlements().get(r.reservationId());
        if (settlement != null && (r.state() == BudgetReservation.State.RELEASED || r.state() == BudgetReservation.State.SETTLED)) {
            if (!settlement.state().name().equals(r.state().name()) || !settlement.charge().currency().equals(r.reserved().currency())
                    || charge != null && !charge.amount().equals(settlement.charge()))
                throw new IllegalStateException("Tool settlement mismatch");
            return new ToolObservation.Cost(r.state().name(), currency, settlement.charge().amount().toPlainString(), null, false);
        }
        boolean held = r.state() == BudgetReservation.State.RESERVED || r.state() == BudgetReservation.State.PENDING_RECONCILIATION;
        return new ToolObservation.Cost(r.state().name(), currency, null, held ? r.reserved().amount().toPlainString() : null, true);
    }

    private static ToolObservation.Statistics summarize(ToolObservation.Query query, Observed observed) {
        var values = observed.items();
        var states = new EnumMap<Invocation.State, Long>(Invocation.State.class);
        for (var state : Invocation.State.values()) states.put(state, 0L);
        values.forEach(i -> states.merge(i.state(), 1L, Long::sum));
        long determined = values.stream().filter(i -> i.state().terminal() && i.state() != Invocation.State.UNKNOWN).count();
        var rate = determined == 0 ? null : BigDecimal.valueOf(states.get(Invocation.State.SUCCEEDED)).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(determined), 2, RoundingMode.HALF_UP).toPlainString();
        var currencies = new TreeMap<String, BigDecimal[]>();
        for (var i : values) {
            var c = i.cost();
            if (c.currency() == null) continue;
            var total = currencies.computeIfAbsent(c.currency(), ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (c.settledAmount() != null) total[0] = total[0].add(new BigDecimal(c.settledAmount()));
            if (c.reservedAmount() != null) total[1] = total[1].add(new BigDecimal(c.reservedAmount()));
        }
        var durations = values.stream().filter(i -> i.state().terminal() && i.state() != Invocation.State.UNKNOWN)
                .map(i -> i.timings().terminalSpanMillis()).filter(Objects::nonNull).sorted().toList();
        return new ToolObservation.Statistics(query, "CURRENT_SUBJECT", observed.at(), values.size(), values.stream().mapToInt(ToolObservation.Item::attemptCount).sum(),
                values.stream().mapToInt(ToolObservation.Item::retryCount).sum(), Map.copyOf(states), determined, rate,
                (int) values.stream().filter(i -> "FREE".equals(i.billingMode())).count(), (int) values.stream().filter(i -> "CHARGE".equals(i.billingMode())).count(),
                (int) values.stream().filter(i -> i.cost().unknown()).count(), (int) values.stream().filter(i -> "0".equals(i.cost().settledAmount())).count(),
                currencies.entrySet().stream().map(e -> new ToolObservation.CurrencyTotals(e.getKey(), e.getValue()[0].toPlainString(), e.getValue()[1].toPlainString())).toList(),
                percentile(durations, .5), percentile(durations, .95), values.stream().filter(i -> i.inputBytes() != null).mapToLong(ToolObservation.Item::inputBytes).sum(),
                (int) values.stream().filter(i -> i.inputBytes() == null).count(), null, observed.scan().records().size(), ToolObservation.STATISTICS_SCAN_LIMIT,
                observed.scan().more(), observed.scan().historicalIncomplete());
    }

    private static Long percentile(List<Long> values, double fraction) {
        return values.isEmpty() ? null : values.get((int) Math.ceil(values.size() * fraction) - 1);
    }

    private static String encode(String fingerprint, ToolObservationStore.Candidate last) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("v1|" + fingerprint + "|" + last.acceptedAt() + "|" + last.key()).getBytes(StandardCharsets.UTF_8));
    }

    private static ToolObservationStore.Position decode(String cursor, String fingerprint, ToolObservation.Query query) {
        if (cursor == null) return null;
        try {
            ContractChecks.text(cursor, "cursor", 256);
            var fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            ContractChecks.require(fields.length == 4 && fields[0].equals("v1") && fields[1].equals(fingerprint), "Cursor query mismatch");
            var at = Long.parseLong(fields[2]);
            ContractChecks.digest(fields[3], "cursor key");
            ContractChecks.require(at >= query.from().toEpochMilli() && at < query.until().toEpochMilli(), "Cursor window mismatch");
            return new ToolObservationStore.Position(at, fields[3]);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid tool observation cursor");
        }
    }
}
