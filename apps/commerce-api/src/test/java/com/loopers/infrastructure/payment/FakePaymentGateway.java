package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentCreateCommand;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentTransaction;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 테스트 전용 가짜 PG. 운영 빈이 아니며 FakePaymentGatewayConfig 를 @Import 한 테스트에서만 쓴다.
 * 다음 create 의 결과를 테스트가 미리 정하고, create 호출 횟수·저장된 거래·거래 상태 변경을 관찰·조작할 수 있다.
 * 무작위 지연·확률은 없고, 카드 번호는 저장하지 않으며, 로그도 남기지 않는다.
 */
public class FakePaymentGateway implements PaymentGateway {

    /**
     * ACCEPT_PENDING: 거래 생성 + 202 PENDING 수락.
     * REJECT_NO_EFFECT: 거래 없이 효과 0건 확정 거절.
     * CREATE_BUT_LOSE_RESPONSE: 거래는 만들지만 응답이 유실되어 UNKNOWN 을 돌려준다.
     */
    public enum NextCreate {
        ACCEPT_PENDING, REJECT_NO_EFFECT, CREATE_BUT_LOSE_RESPONSE
    }

    private static final String TRANSACTION_KEY_FORMAT = "FAKE-TX-%06d";

    private volatile NextCreate nextCreate = NextCreate.ACCEPT_PENDING;
    private final AtomicInteger createCount = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<String, PaymentTransaction> transactions = new ConcurrentHashMap<>();

    public void willAcceptPending() {
        nextCreate = NextCreate.ACCEPT_PENDING;
    }

    public void willRejectNoEffect() {
        nextCreate = NextCreate.REJECT_NO_EFFECT;
    }

    public void willCreateButLoseResponse() {
        nextCreate = NextCreate.CREATE_BUT_LOSE_RESPONSE;
    }

    public int createCount() {
        return createCount.get();
    }

    /**
     * 저장된 거래 전부. 키가 "FAKE-TX-000001" 처럼 순번이라 키 정렬이 곧 생성 순서다.
     */
    public List<PaymentTransaction> transactions() {
        return transactions.values().stream()
            .sorted(Comparator.comparing(PaymentTransaction::transactionKey))
            .toList();
    }

    public void markSuccess(String transactionKey) {
        changeStatus(transactionKey, PaymentTransaction.Status.SUCCESS);
    }

    public void markFailed(String transactionKey) {
        changeStatus(transactionKey, PaymentTransaction.Status.FAILED);
    }

    public void reset() {
        nextCreate = NextCreate.ACCEPT_PENDING;
        createCount.set(0);
        sequence.set(0);
        transactions.clear();
    }

    @Override
    public PaymentCreateResult create(PaymentCreateCommand command) {
        createCount.incrementAndGet();
        return switch (nextCreate) {
            case REJECT_NO_EFFECT -> PaymentCreateResult.rejectedNoEffect();
            case ACCEPT_PENDING -> PaymentCreateResult.accepted(store(command).transactionKey());
            case CREATE_BUT_LOSE_RESPONSE -> {
                store(command);
                yield PaymentCreateResult.unknown();
            }
        };
    }

    @Override
    public List<PaymentTransaction> findAllByOrderId(String providerOrderId) {
        return transactions().stream()
            .filter(transaction -> transaction.providerOrderId().equals(providerOrderId))
            .toList();
    }

    @Override
    public Optional<PaymentTransaction> findByTransactionKey(String transactionKey) {
        return Optional.ofNullable(transactions.get(transactionKey));
    }

    private PaymentTransaction store(PaymentCreateCommand command) {
        String key = String.format(TRANSACTION_KEY_FORMAT, sequence.incrementAndGet());
        PaymentTransaction transaction = new PaymentTransaction(
            key, command.providerOrderId(), command.amount(), PaymentTransaction.Status.PENDING);
        transactions.put(key, transaction);
        return transaction;
    }

    private void changeStatus(String transactionKey, PaymentTransaction.Status status) {
        PaymentTransaction changed = transactions.computeIfPresent(transactionKey,
            (key, found) -> new PaymentTransaction(key, found.providerOrderId(), found.amount(), status));
        if (changed == null) {
            throw new IllegalArgumentException("가짜 PG 에 없는 거래다: " + transactionKey);
        }
    }
}