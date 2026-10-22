# 5주차 설계 · 포인트 결제 → 로컬 테스트 PG 교체

- 이 문서 범위: 0장 이름 대조표 · 1장 트랜잭션 경계(예약 저장 형태, 결제 시도 식별자, create 응답 매핑 포함) · 2장 동시성 방식 · 3장 발송 시작 기록 · 4장 같은 주문의 confirm 재요청(202·200 응답 본문 포함) · 5장 PG 성공 뒤 내부 저장(TX B) 실패 · 6장 조율 위치 · 7장 PG 조회 결과가 0건·2건 이상·불일치일 때 · 8장 상태 모델(결제 시도·주문·예약 상태 전이) · 부록 결정 필요(현재 없음)
- 이 문서 범위 밖: PG adapter의 HTTP 요청·응답 매핑 세부, callback·reconcile의 상세 계약(요청 형식·검증 규칙·오류 코드), circuit breaker·retry 수치, 테스트 구성. 과제 2·5·6장에 해당하며 별도 문서로 다룬다. 단, callback·reconcile 중 조율 위치(6장)와 202·200 응답 모양(4-6)은 이 문서에서 정했다.
- 표기 규칙: **과제 근거**는 원문 섹션명과 문장을 인용한다. **판단:** 은 과제가 정하지 않아 내가 고른 것이다. **결정 필요** 는 이 문서에서 정하지 않고 선택지만 남긴 것이다.

---

## 0. 이름 대조표

현재 코드를 읽고 맞춘 이름이다. 기존에 없는 이름은 `신규` 로 표시한다.

| 역할 | 이름 | 상태 |
|---|---|---|
| 주문 상태 (기존 2개) | `Order.OrderStatus.DRAFT`, `CONFIRMED` | 기존 |
| 주문 상태 (결제 대기·실패) | `Order.OrderStatus.PAYMENT_PENDING`, `PAYMENT_FAILED` | **신규** (enum 값 추가) |
| 주문 금액 스냅샷 | `Order.getTotalAmount()`, `Order.paidAmount` | 기존 |
| 주문 결제수단 | `Order.paymentMethod`, `Order.PaymentMethod.POINT`·`PG` | **신규** (8-2 결정 ⑮ 참조) |
| 주문 품목 | `OrderItem` (productId, quantity, unitPrice) | 기존 |
| 주문 소유권 판정 | `Order.isOwnedBy(userId)` | 기존 |
| 주문 행 잠금 조회 | `OrderRepository.findByIdForUpdate(orderId)` | **신규** (`@Lock(PESSIMISTIC_WRITE)`) |
| 주문 결제 시작 | `Order.startPayment()` | **신규** (잠긴 행의 상태 변경) |
| 주문 결과 반영 | `OrderRepository.finishIfPaymentPending(orderId, status, paidAmount)` | **신규** |
| 결제 시도 | `PaymentAttempt` | **신규** |
| 결제 시도 상태 | `REQUESTED`, `PENDING`, `UNKNOWN`, `SUCCESS`, `FAILED` | **신규** (미종결 3개 + 종결 2개, 전이는 8-1) |
| 재고 예약 | `StockReservation` | **신규** |
| 예약 상태 | `RESERVED`, `CONSUMED`, `RELEASED` | **신규** |
| 재고 차감 | `ProductRepository.deductStockIfEnough(productId, quantity)` | 기존 (그대로 재사용) |
| 재고 복원 | `ProductRepository.restoreStock(productId, quantity)` | **신규** (`stock = stock + n`) |
| 재고 최종 설정 | `ProductRepository.updateStock(productId, quantity)` | 기존 |
| 오류 타입 | `ErrorType.BAD_REQUEST`, `UNAUTHORIZED`, `NOT_FOUND`, `CONFLICT` | 기존 (신규 타입 불필요, 4장 참조) |
| 예외 | `CoreException(ErrorType, customMessage)` | 기존 |
| 응답 포맷 | `ApiResponse<T>` | 기존 |
| 상태 코드 분기 (202/200) | `ResponseEntity<ApiResponse<T>>` | **신규** (기존 컨트롤러는 `@ResponseStatus` 고정값만 사용) |
| 예약 테이블 | `stock_reservations` (order_id, product_id, quantity, status) | **신규** (1-4 참조) |
| 결제 시도의 외부 주문 식별자 | `PaymentAttempt.providerOrderId` | **신규** (컬럼으로 저장, 1-5 참조) |
| callback·reconcile 조율 | `PaymentFacade` | **신규** (6장 참조) |
| TX B 공용 서비스 | `PaymentResultService` | **신규** (별도 `@Transactional` 빈, 6장 참조) |
| PG 조회 포트 | `PaymentGateway` (domain 인터페이스, infrastructure 구현) | **신규** (7장 참조) |
| PG 목록·단건 조회 | `PaymentGateway.findAllByOrderId(providerOrderId)`, `findByTransactionKey(transactionKey)` | **신규** (판정은 목록 조회만 사용, 7-3 참조) |
| 충돌 기록 | `PaymentAttempt.conflicted`, `conflictReason`, `conflictedAt` | **신규** (상태 컬럼과 별개, 7-2 참조) |
| 실패 사유 | `PaymentAttempt.failureReason`, `FailureReason.REJECTED_BEFORE_CREATE`·`PG_FAILED` | **신규** (1-6 결정 ⑰ 참조) |
| 스케줄러 조회 추적 | `PaymentAttempt.lookupCount`, `nextLookupAt`, `manualCheckRequired` | **신규** (상태 컬럼과 별개, 5-3 결정 ⑫ 참조) |

이번 변경으로 쓰이지 않게 되는 기존 코드 (구현 시 제거 대상으로 함께 확인한다)

| 대상 | 이유 |
|---|---|
| `OrderRepository.confirmIfDraft` | DRAFT → CONFIRMED 직행 전이가 없어지고, ② DRAFT → PAYMENT_PENDING 과 ⑦ PAYMENT_PENDING → CONFIRMED 로 쪼개진다 |
| `PointService.deduct` | 주문 확정에서 포인트를 차감하지 않는다. 호출자가 `OrderFacade.confirmOrder` 뿐이다 |

> **과제 근거** (1. 교체 범위와 상태 설계)
> "신규 PG 결제 시도·외부 거래·결과 확인 경계 추가 / 포인트 차감 0회"
> "포인트 충전·조회 기존 API를 유지하되 주문 결제에서 분리"

> **과제 근거** (4. 재고 확보와 결과 반영)
> "상태 이름은 위 의미를 유지하는 범위에서 기존 모델에 맞게 정합니다. 외부 PENDING을 CONFIRMED로 바꾸지 않습니다."

---

## 1. 트랜잭션 경계

### 1-1. 원칙

> **과제 근거** (1. 교체 범위와 상태 설계)
> "설계 문서에 로컬 TX 전후, 외부 HTTP 호출, 결과 적용 경계를 표시합니다. PG 호출 동안 DB 트랜잭션·재고 잠금을 계속 보유하지 않습니다."

> **과제 근거** (4. 재고 확보와 결과 반영)
> "1. 로컬 TX A: 주문·상품을 검사하고 필요한 재고를 확보합니다. 시도·예약·결제 대기 상태를 같은 TX에 저장합니다. 부족·삭제·잘못된 입력이면 PG 호출 없이 전체 rollback합니다."
> "2. TX 밖: 발송 권한을 가진 실행이 PG를 호출합니다. 다른 실행은 이미 저장된 상태를 조회합니다."
> "3. 로컬 TX B: 검증한 PG 결과를 시도·주문·예약에 한 번만 적용합니다."

4주차까지는 주문 확정 전체가 `OrderFacade.confirmOrder` 의 단일 `@Transactional` 안에 있었다. 이번에는 그 하나를 TX A / TX 밖 / TX B 세 구간으로 쪼갠다.

### 1-2. 단계별 경계

**로컬 TX A** (DB 커넥션·락 보유, commit 으로 종료)

| 단계 | 동작 | 락 방식 |
|---|---|---|
| ① | 주문 행 잠금. 소유권 → 기존 시도 → DRAFT 순으로 확인하고, 기존 시도가 있으면 키·요청 일치를 본다 | 비관적 락 (`SELECT … FOR UPDATE`) |
| ② | 주문 `DRAFT` → `PAYMENT_PENDING` | ①의 행 락을 그대로 쓰는 일반 UPDATE |
| ③ | 재고 차감 + 예약 `RESERVED` 저장. 품목을 `productId` 오름차순으로 처리 | 조건부 UPDATE (`WHERE stock >= n`) |
| ④ | 결제 시도 저장 (`status = REQUESTED`) | INSERT + `UNIQUE(order_id)` |

④까지 끝나고 **commit 하는 순간을 발송 시작 기록으로 본다** (3장에서 상세히 다룬다). 어느 단계든 거절되면 PG 호출 없이 전체 rollback 되어 주문은 `DRAFT` 로 남고 재고도 원상태다.

**TX 밖** (DB 커넥션 없음, 락 없음, 자동 재시도 없음)

| 단계 | 동작 |
|---|---|
| ⑤ | PG `POST /api/v1/payments` 호출 1회 |

> **과제 근거** (3. 기존 주문 확정에 PG 연결)
> "발송 시작을 영속 기록한 뒤에는 자동으로 create를 다시 보내지 않습니다. … 생성 HTTP client·프록시·회복 라이브러리의 숨은 재시도도 끕니다."

**로컬 TX B** (결과 반영, 세 입구가 공유)

| 단계 | 동작 | 락 방식 |
|---|---|---|
| ⑥ | 결과 반영 문지기. 결제 시도를 미종결(`REQUESTED`·`PENDING`·`UNKNOWN`)일 때만 갱신. **0행이면 아무 효과 없이 종료** | 조건부 UPDATE `WHERE status IN ('REQUESTED', 'PENDING', 'UNKNOWN')` |
| ⑦ | 주문 상태 갱신. `WHERE status = PAYMENT_PENDING`, 성공이면 `CONFIRMED` + `paidAmount`, 실패면 `PAYMENT_FAILED` | 조건부 UPDATE |
| ⑧ | 예약 갱신. `WHERE status = RESERVED`, 성공이면 `CONSUMED`, 실패면 `RELEASED` + **1행일 때만** `stock = stock + n` | 조건부 UPDATE |

⑥이 단독 승자를 정하므로 ⑦⑧은 한 TX 안에서 한 번만 실행된다. 그래도 조건을 WHERE 에 두는 이유는 과거에 종결된 상태를 덮어쓰지 않기 위한 이중 안전망이다.

**TX B 의 세 입구**

| 입구 | 들어오는 결과 | 비고 |
|---|---|---|
| create 응답 | ⑤의 동기 응답 | 생성 수락(`PENDING`) 또는 효과 0건 확정 거절 |
| callback | PG 알림 수신 → **PG 권위 조회 후** | 알림 본문의 상태 문자열만으로는 반영하지 않는다 |
| reconcile | 사용자 요청 → **PG 권위 조회 후** | 콜백이 없을 때의 기본 복구 경로 |

> **과제 근거** (5. callback·조회·회복 전략)
> "알림의 성공 문자열만으로 주문을 확정하지 않습니다. 고정한 PG adapter의 조회 결과에서 제공자 계정 범위·주문·transactionKey·저장 금액을 확인한 뒤 적용합니다."
> "같은 거래의 callback-first와 lookup-first가 같은 최종 상태로 수렴합니다."

**생성 수락(PENDING) 처리**

create 응답이 `PENDING` 이면 ⑥에서 `REQUESTED` → `PENDING` 으로 바꾸고 `transactionKey` 를 저장한다. 조건은 `WHERE status IN ('REQUESTED', 'UNKNOWN')` 이다. 조회로 거래를 확인한 경우(결정 ⑪, 8-1)도 같은 UPDATE 를 쓴다. **⑦⑧은 실행하지 않는다.**

**미확인(UNKNOWN) 처리**

timeout·응답 유실·조회 불가는 ⑥에서 `REQUESTED` → `UNKNOWN` 만 기록하고 **⑦⑧을 실행하지 않는다.** 조건은 `WHERE status = 'REQUESTED'` 로, 이미 `PENDING` 인 시도는 되돌리지 않는다. 주문은 `PAYMENT_PENDING`, 예약은 `RESERVED` 로 유지된다.

> **과제 근거** (2. 로컬 테스트 PG 계약과 adapter)
> "반대로 timeout, 본문 없는 422, 다른 오류 code의 4xx·5xx, 잘못된 JSON·식별·금액은 효과 0건의 증거가 아닙니다. 다른 확정 결과가 없는 해당 시도는 UNKNOWN·예약 유지로 두고 기존 거래 조회 경로를 사용합니다."

> **과제 근거** (4. 재고 확보와 결과 반영)
> "timeout만으로 실패·예약 반환을 결정하지 않습니다."

`PENDING`·`UNKNOWN` 은 종결 상태가 아니다. 이후 callback·reconcile 이 권위 조회로 `SUCCESS`·`FAILED` 를 확인하면 ⑥의 WHERE 에 걸려 종결된다.

### 1-3. 전체 흐름

```mermaid
flowchart TD
    REQ["confirm 요청<br/>POST /api/v1/orders/:orderId/confirm<br/>paymentAttemptKey + cardType + cardNo"]

    subgraph TXA["로컬 TX A · DB 커넥션·락 보유"]
        direction TB
        A1["① 주문 행 잠금<br/>SELECT ... FOR UPDATE<br/>소유권 → 기존 시도 → DRAFT 확인<br/>락: 비관적 락"]
        A2["② 주문 DRAFT → PAYMENT_PENDING<br/>락: ①의 행 락으로 일반 UPDATE"]
        A3["③ 재고 차감 + 예약 RESERVED 저장<br/>WHERE stock >= n · productId 오름차순<br/>락: 조건부 UPDATE"]
        A4["④ 결제 시도 저장 REQUESTED<br/>UNIQUE order_id · UNIQUE user_id+attempt_key<br/>락: INSERT + 유일 제약"]
        A1 --> A2 --> A3 --> A4
    end

    REJ["거절 → PG 호출 없이 전체 rollback<br/>주문 DRAFT · 재고 원상태"]
    CM["commit = 발송 시작 기록<br/>여기서 모든 락 해제"]

    subgraph OUT["TX 밖 · DB 커넥션 없음 · 락 없음 · 자동 재시도 없음"]
        direction TB
        P1["⑤ PG create 호출 1회<br/>POST /api/v1/payments"]
        P2{"응답 판정"}
        P1 --> P2
    end

    CB["callback 수신<br/>→ PG 권위 조회·검증"]
    RC["reconcile 요청<br/>→ PG 권위 조회·검증"]

    subgraph TXB["로컬 TX B · 결과를 한 번만 반영"]
        direction TB
        B1["⑥ 결과 반영 문지기<br/>결제 시도 조건부 UPDATE<br/>WHERE 미종결 REQUESTED·PENDING·UNKNOWN<br/>락: 조건부 UPDATE"]
        BX["0행 → 효과 없이 종료<br/>종결된 결과를 덮지 않음"]
        B2["⑦ 주문 상태 조건부 UPDATE<br/>WHERE PAYMENT_PENDING<br/>성공 CONFIRMED + paidAmount<br/>실패 PAYMENT_FAILED<br/>락: 조건부 UPDATE"]
        B3["⑧ 예약 조건부 UPDATE<br/>WHERE RESERVED<br/>성공 CONSUMED · 추가 차감 없음<br/>실패 RELEASED + 1행일 때만 stock = stock + n<br/>락: 조건부 UPDATE"]
        B1 -->|"1행"| B2 --> B3
        B1 -->|"0행"| BX
    end

    UNK["⑥만 실행 · REQUESTED → UNKNOWN<br/>⑦⑧ 실행하지 않음<br/>주문 PAYMENT_PENDING · 예약 RESERVED 유지"]
    PEND["⑥만 실행 · REQUESTED → PENDING + transactionKey 저장<br/>WHERE status IN REQUESTED·UNKNOWN<br/>⑦⑧ 실행하지 않음"]

    REQ --> A1
    A1 -.->|"소유권·시도·DRAFT·재고 거절"| REJ
    A4 --> CM --> P1
    P2 -->|"202 PENDING<br/>생성 수락"| PEND
    P2 -->|"422 + 정확한 code + false<br/>+ orderId·amount 일치"| B1
    P2 -->|"timeout · 응답 유실<br/>본문 없는 422 · 기타 4xx·5xx"| UNK
    CB --> B1
    RC --> B1
```

create 응답에서 `SUCCESS` 는 오지 않는다. 과제 계약이 생성 수락을 `202 PENDING` 으로 고정했기 때문이고, 검증된 `SUCCESS` 는 callback·reconcile 입구로만 들어온다.

> **과제 근거** (2. 로컬 테스트 PG 계약과 adapter)
> "생성 수락 응답은 HTTP 202로 둡니다."
> "PENDING, SUCCESS, FAILED. 생성 수락과 최종 성공을 구분"

### 1-4. 예약 저장 형태 (확정)

예약은 **별도 테이블 `stock_reservations`** 에 둔다. 컬럼은 `order_id`, `product_id`, `quantity`, `status`(`RESERVED` / `CONSUMED` / `RELEASED`) 다. 기존 `Product.stock.quantity` 는 **"지금 팔 수 있는 가용 재고"** 의 의미로 그대로 쓴다. ③에서 차감하면 그 즉시 가용 재고가 줄고, ⑧의 실패 반환에서만 다시 늘어난다.

> **과제 근거** (4. 재고 확보와 결과 반영)
> "PG 성공 뒤 재고 부족을 발견하는 일을 피하기 위해 PG 호출 전에 기존 재고를 주문에 확보합니다. 기존 가용 재고를 차감하고 주문별 확보 수량·상태를 저장하는 최소 예약 방식으로 구현할 수 있습니다."

**판단:** 과제가 "주문별 확보 수량·상태"를 요구하므로 주문 단위의 상태 전이를 담을 행이 필요하다. ⑧의 `WHERE status = RESERVED` 가 예약 행 하나에 걸리므로, 같은 실패 알림이 두 번 와도 두 번째는 0행이 되어 반환이 한 번으로 끝난다. 4주차의 상품 조회·정렬·캐시는 `Product.stock.quantity` 를 그대로 읽으므로 변경이 없다.

기각한 대안

| 대안 | 기각 이유 |
|---|---|
| `products` 에 `reserved_quantity` 컬럼 추가 | 주문별 반환 여부를 기록할 곳이 없다. 같은 실패 알림이 두 번 오면 두 번 모두 "예약을 돌려준다"가 되어 재고 5 → 7 반례를 막을 수 없다 |
| `order_items` 에 예약 상태 컬럼 추가 | 주문 당시의 수량·단가 기록이던 `OrderItem` 에 가변 상태가 섞인다. 과거 주문을 "기존 사실을 그대로 조회"해야 한다는 과제 표와 충돌한다 |

> **과제 근거** (1. 교체 범위와 상태 설계)
> "이전 포인트 결제 주문 → 기존 사실을 그대로 조회 / 결제수단 POINT·금액·결과·당시 품목 정보"
> "접근 구분과 과거 주문 보호"

**관리자 재고 설정과의 관계.** 기존 재고 설정 API(`ProductRepository.updateStock`)는 `Product.stock.quantity` 만 바꾸고 `stock_reservations` 는 건드리지 않는다. 관리자가 설정하는 값은 "예약을 뺀 가용 수량"이고, 이미 확보된 예약은 PG 결과가 나올 때까지 그대로 남는다.

> **과제 근거** (4. 재고 확보와 결과 반영)
> "기존 재고 설정 API는 예약을 제외한 가용 수량을 최종 설정하는 의미로 연결하고, 관리자 변경도 같은 재고 행의 보호 규칙을 사용합니다. 예약 수량을 관리자 설정 때문에 지우거나 PG 성공 때 다시 차감하지 않습니다."

**주문 품목과 겹치는 정보.** `product_id` 와 `quantity` 는 `OrderItem` 에도 있다. 그래도 예약은 주문 기록의 복사가 아니라 **재고 쪽 기록**이다. 주문 품목은 "무엇을 얼마에 샀는가"를 주문 시점에 고정한 기록이고, 예약은 "재고에서 얼마를 빼 두었고 지금 어느 상태인가"를 재고 관점에서 추적하는 행이다. 수명도 다르다. 주문 품목은 영구 보존되지만, 예약은 종결(`CONSUMED`·`RELEASED`)되면 역할이 끝난다.

### 1-5. 결제 시도에 보존하는 식별자

결제 시도 한 행에 세 식별자를 역할별로 나눠 저장한다.

| 식별자 | 역할 | 정해지는 시점 |
|---|---|---|
| `attemptKey` | 고객이 보낸 멱등 키. 같은 요청인지 판별한다 (4장) | confirm 요청 |
| `providerOrderId` | PG 에 보내는 주문 식별 문자열 (예: 주문 42 → `LP-ORD-0000000042`). 생성과 조회에 같은 값을 쓴다 | TX A ④ |
| `transactionKey` | PG 가 발급한 거래 키. 거래 단건 조회에 쓴다 | ⑤ 응답 또는 callback |

`providerOrderId` 는 **컬럼으로 저장한다.** 과제가 보존을 요구하므로 결정 필요 항목이 아니다.

> **과제 근거** (3. 기존 주문 확정에 PG 연결)
> "내부 시도 키·providerOrderId·외부 transactionKey의 역할을 나눠 DB에 보존합니다."

> **과제 근거** (2. 로컬 테스트 PG 계약과 adapter)
> "내부 주문 42를 LP-ORD-0000000042 로 매핑했다면 생성과 조회에 동일하게 사용합니다."

보충: 포맷 함수로만 파생하면 포맷을 바꾼 순간 진행 중인 미확인 시도를 orderId 조회(`GET /api/v1/payments?orderId=`)로 찾지 못한다. 저장해 두면 과거 시도는 저장된 값으로 조회된다.

### 1-6. create 응답 매핑과 효과 0건 확정 거절

⑤의 create 응답 하나를 내부 상태로 옮기는 규칙이다. 핵심은 **"PG 에 거래가 아예 안 생겼다"를 확정할 수 있는 응답과, 그냥 모르는 응답을 가르는 것**이다. 앞의 것만 실패로 종결하고 예약을 되돌린다.

#### 효과 0건 확정 거절의 조건

`REJECTED_NO_EFFECT` 로 매핑하는 조건은 네 가지이며 **모두** 충족해야 한다.

| 조건 | 확인 대상 |
|---|---|
| HTTP 상태 422 | 응답 상태 코드 |
| 정확한 code | 본문 `code` 가 `TEST_PG_REJECTED_BEFORE_CREATE` |
| `transactionCreated` 가 boolean `false` | 본문 `transactionCreated` |
| 요청 `orderId`·`amount` 일치 | 본문 `orderId`·`amount` 가 우리가 보낸 값과 같은지 |

> **과제 근거** (2. 로컬 테스트 PG 계약과 adapter · 공통 · 거래 생성 전 확정 거절과 UNKNOWN 구분)
> "adapter는 고정한 로컬 PG의 생성 응답에서 422 + 정확한 code + boolean false + 요청 orderId·amount 일치를 모두 확인한 경우에만 REJECTED_NO_EFFECT 로 매핑합니다. 이 내부 관찰은 결제 FAILED·예약 한 번 반환으로 연결하고, 거래가 없으므로 transactionKey를 꾸며 저장하지 않습니다."

> **과제 근거** (2. 로컬 테스트 PG 계약과 adapter · 공통 · 거래 생성 전 확정 거절과 UNKNOWN 구분)
> "반대로 timeout, 본문 없는 422, 다른 오류 code의 4xx·5xx, 잘못된 JSON·식별·금액은 효과 0건의 증거가 아닙니다. 다른 확정 결과가 없는 해당 시도는 UNKNOWN·예약 유지로 두고 기존 거래 조회 경로를 사용합니다. 일반 조회 404·빈 목록도 이 생성 거절과 다릅니다."

**구현 주의 두 가지.**

- `transactionCreated` 가 문자열 `"false"` 이면 통과시키지 않는다. 과제가 boolean `false` 를 명시했다.
- `code` 는 정확히 일치로 비교한다. 대소문자 무시나 부분 일치(`contains`·`startsWith`)를 쓰지 않는다.

#### create 응답별 표

| create 응답 | adapter 관찰 | 결제 시도 | 주문 | 예약 |
|---|---|---|---|---|
| 202 + 본문 `status: PENDING` | 생성 수락 | `PENDING` + `transactionKey` 저장 | `PAYMENT_PENDING` 유지 | `RESERVED` 유지 |
| 422, 네 조건 모두 충족 | `REJECTED_NO_EFFECT` | `FAILED` + 사유 `REJECTED_BEFORE_CREATE`. `transactionKey` 없음 | `PAYMENT_FAILED` | `RELEASED` + `stock = stock + n` 1회 |
| 본문 없는 422 | 증거 아님 | `UNKNOWN` | `PAYMENT_PENDING` 유지 | `RESERVED` 유지 |
| 422 지만 `code` 가 다름 | 증거 아님 | `UNKNOWN` | `PAYMENT_PENDING` 유지 | `RESERVED` 유지 |
| 그 밖의 4xx·5xx | 증거 아님 | `UNKNOWN` | `PAYMENT_PENDING` 유지 | `RESERVED` 유지 |
| 잘못된 JSON | 증거 아님 | `UNKNOWN` | `PAYMENT_PENDING` 유지 | `RESERVED` 유지 |
| timeout·응답 유실 | 증거 아님 | `UNKNOWN` | `PAYMENT_PENDING` 유지 | `RESERVED` 유지 |
| 422 + 정확한 code + `false` 인데 `orderId`·`amount` 불일치 | 증거 아님 + 이상 신호 | `UNKNOWN` + 충돌 표시 (결정 ⑱) | `PAYMENT_PENDING` 유지 | `RESERVED` 유지 |

**중복 적용도 반환을 늘리지 않는다.** create 응답도 callback·reconcile 과 같은 TX B 문지기(⑥, 미종결일 때만)를 거친다. 같은 거절이 두 번 적용되면 두 번째는 0행이 되어 ⑦⑧이 실행되지 않는다.

> **과제 근거** (2. 로컬 테스트 PG 계약과 adapter · 공통 · 거래 생성 전 확정 거절과 UNKNOWN 구분)
> "작은 HTTP 계약 테스트에서 위 응답은 거래 0건·예약 반환 1회, 거래 생성 뒤 응답 유실은 거래 1건·UNKNOWN·예약 유지·create 추가 0회로 대조합니다. 동일한 거절의 중복 적용도 반환을 늘리지 않아야 합니다."

#### 결정 ⑰ 실패 사유를 결제 시도에 저장 (확정)

결제 시도에 실패 사유(`PaymentAttempt.failureReason`, **신규**)를 저장한다. 값은 `REJECTED_BEFORE_CREATE`(생성 전 확정 거절)와 `PG_FAILED`(조회로 확인된 `FAILED`) 두 개다.

**판단:** `FAILED` 에 도달하는 경로가 둘이고(조회 결과 `FAILED`, create 의 생성 전 거절) 성격이 다르다. 데이터로 구분해 두면 주문 조회·테스트·PR 설명에서 "어느 쪽 실패인가"를 바로 말할 수 있다.

기각: "`FAILED` 인데 `transactionKey` 가 없으면 생성 전 거절"로 추론. 의미가 암묵적이고, `transactionKey` 없이 `FAILED` 가 되는 다른 경로가 생기면 추론이 깨진다.

#### 결정 ⑱ orderId·amount 불일치 422 (확정)

네 조건 중 `orderId`·`amount` 일치만 어긋난 422 는 `UNKNOWN` 으로 두고 **충돌 표시**(7-2 의 `conflicted`·`conflictReason`·`conflictedAt`)를 함께 기록한다. 예약은 유지한다.

**판단:** 7장에서 조회 결과의 금액·식별 불일치를 충돌로 기록하기로 정했다. 같은 기준을 create 응답에도 적용한다. 우리가 보낸 값과 다른 값이 돌아온 것은 어느 경로든 이상 신호다.

기각: `UNKNOWN` 만 기록. 이상 신호가 평범한 미확인에 묻혀 사라진다.

---

## 2. 동시성 방식

**원칙:** 읽고 따질 게 많으면 비관적 락, WHERE 한 줄로 승자가 정해지면 조건부 UPDATE, 유일 제약은 안전망.

| 대상 | 시점 | 방식 | 이유 |
|---|---|---|---|
| 주문 행 | TX A ① | 비관적 락 `SELECT … FOR UPDATE` | 소유권·기존 시도 유무·키 일치·`DRAFT` 여부를 **순서대로 따져 응답을 달리 해야** 해서 한 WHERE 로 승자를 못 정한다. 주문은 주인 1명만 건드려 경합이 낮아 락 비용이 작다. 잠근 뒤 읽으면 최신 커밋 값이라 REPEATABLE READ 스냅샷 함정(먼저 읽은 스냅샷으로 판정해 중복 시도를 놓치는 일)을 피한다 |
| 주문 상태 `DRAFT`→`PAYMENT_PENDING` | TX A ② | 잠긴 행에 일반 UPDATE | ①이 이미 단독 접근을 보장하므로 WHERE 조건을 중복으로 걸 이유가 없다 |
| 재고 | TX A ③ | 조건부 UPDATE `WHERE stock >= n`, `productId` 오름차순 | 인기 상품은 여러 주문이 같은 행에 몰려 경합이 높고, 판정이 "충분한가" 한 줄이다. 4주차 방식을 그대로 유지한다. 오름차순 고정으로 교차 데드락을 피한다 |
| 결제 시도 생성 | TX A ④ | 주문 락 안에서 INSERT + `UNIQUE(order_id)`, `UNIQUE(user_id, attempt_key)` | 락이 이미 1건을 보장하지만, 락 경로를 거치지 않는 코드가 생기면 DB가 마지막으로 막아주는 안전망이다 |
| 결과 반영 문지기 (결제 시도) | TX B ⑥ | 조건부 UPDATE `WHERE status IN (REQUESTED, PENDING, UNKNOWN)` | create 응답·callback·reconcile 세 입구가 동시에 와도 **1행을 얻는 쪽은 하나뿐**이다. 탈락한 쪽은 락을 기다리지 않고 바로 종료한다. 종결 뒤 늦게 온 상충 응답이 상태를 되돌리지 못한다 |
| 주문 결과 상태 | TX B ⑦ | 조건부 UPDATE `WHERE status = PAYMENT_PENDING` | 이미 `CONFIRMED`·`PAYMENT_FAILED` 가 된 주문을 다시 쓰지 않는다 |
| 예약 소비·반환과 재고 복원 | TX B ⑧ | 조건부 UPDATE `WHERE status = RESERVED` + 1행일 때만 `stock = stock + n` | 중복 실패 알림이 재고를 5 → 7 로 늘리지 못하게 한다. 재고 복원이 예약 상태 전이와 같은 조건·같은 TX 에 묶인다 |

> **과제 근거** (3. 기존 주문 확정에 PG 연결)
> "주문당 한 시도와 시도 키의 요청 일치를 유일 제약·상태 전이로 보호합니다. 메모리 잠금만 사용하지 않습니다."
> "같은 시도의 동시 confirm은 실제 발송 권한을 얻은 실행 하나만 PG POST를 호출합니다."

> **과제 근거** (4. 재고 확보와 결과 반영)
> "성공·반환 상태와 재고 변경을 같은 TX에서 보호해 중복 callback·동시 reconcile이 두 번 확정·반환하지 못하게 합니다."

> **과제 근거** (6. 결정적 검증과 제출)
> "재고 5개에서 수량 2개를 확보했다면 가용 재고는 3개입니다. PG 성공 후에도 3개이며, 확정 실패 후에는 5개입니다. 중복 실패 알림으로 7개가 되거나 UNKNOWN에서 5개로 돌아가면 잘못된 처리입니다."

### 락 순서와 데드락

| 트랜잭션 | 락 획득 순서 |
|---|---|
| TX A | 주문 → 재고 (`productId` 오름차순) |
| TX B | 결제 시도 → 주문 → 예약 → 재고 |

주문과 결제 시도의 순서가 두 TX에서 서로 반대다. 그래도 교차 데드락이 생기지 않는 근거는 **같은 주문의 TX A와 TX B가 시간상 겹치지 않는다**는 것이다.

- TX B 는 결제 시도 행이 커밋된 뒤에만 1행을 얻는다. 그 커밋이 곧 TX A 의 종료다.
- callback 은 `transactionKey` 를 알아야 오고, 그 키는 TX A commit 이후의 ⑤ 호출로 처음 생긴다. TX A 보다 먼저 올 수 없다.
- reconcile 이 TX A 커밋 전에 들어오면 결제 시도가 아직 보이지 않아 조회에서 걸러지고 TX B 에 진입하지 않는다.

**판단:** 격리 수준은 MySQL 8.0 기본값 REPEATABLE READ 를 그대로 둔다. 모든 판정이 잠긴 행 읽기 또는 조건부 UPDATE 라 스냅샷 기반 판정이 없기 때문이다.

---

## 3. 발송 시작 기록

### 결정 (확정)

**TX A 안에서 결제 시도를 `REQUESTED` 로 저장하고, 그 commit 을 발송 시작 기록으로 본다.** 즉 별도의 "선점" 트랜잭션을 두지 않고, 시도 저장과 주문·예약 저장이 같은 TX 에서 한 번에 커밋된다.

> **과제 근거** (3. 기존 주문 확정에 PG 연결)
> "발송 시작을 영속 기록한 뒤에는 자동으로 create를 다시 보내지 않습니다. 발송 기록 이후 프로세스가 멈추면 실제 발송 여부가 불확실할 수 있으므로 재시작 후 기존 거래를 조회합니다."

### 검토한 대안

`READY` 상태로 저장한 뒤, 별도 TX 에서 `WHERE status = READY` 조건부 UPDATE 로 `REQUESTED` 를 선점한 실행만 PG 를 호출하는 방식.

### 반례 비교

| 장면 | 확정안 (REQUESTED 커밋 = 발송 시작) | 대안 (READY 저장 후 별도 TX 선점) |
|---|---|---|
| **호출 전 사망** (TX A commit 직후, ⑤ 전에 프로세스 종료) | 시도가 `REQUESTED` 로 남는다. 실제 거래는 0건인데 상태는 미확인이다. reconcile 의 조회 0건으로 확인되며 자동 재발송은 하지 않는다 | `READY` 로 남아 "아직 안 보냈다"가 명확하다. 다만 과제가 create 자동 재시도를 금지하므로 그 정보로 할 수 있는 일이 없다 |
| **호출 중 사망** (⑤ 전송 후 응답 전 종료) | `REQUESTED` 로 남고, 거래는 1건 있을 수 있다. reconcile 의 권위 조회로 종결한다 | 선점이 끝난 뒤이므로 상태가 `REQUESTED` 로 같다. **차이 없음** |
| **동시 confirm** (같은 주문·같은 키 2건) | 주문 비관적 락 + `UNIQUE(order_id)` 로 INSERT 가 하나만 성공하고, 그 실행만 ⑤를 호출한다 | 선점 UPDATE 에서 1행을 얻은 하나만 ⑤를 호출한다. **차이 없음**. 대신 TX 가 2개라 중간 상태가 하나 더 생긴다 |
| **READY 방치** (READY 저장 후 선점 TX 전에 종료) | 해당 상태가 없다 | `READY` 가 영구 잔류한다. 주문은 `PAYMENT_PENDING`, 예약은 `RESERVED` 인데 PG 에는 거래가 없고 자동 재발송도 금지라 reconcile 조회는 0건이다. **수동 정리 대상 상태가 하나 더 늘어난다** |

**판단:** 대안이 이득을 주는 장면은 "호출 전 사망"뿐이고, 그 이득(재발송 가능 판단)은 과제가 자동 재발송을 금지해 쓸 수 없다. 반대로 대안은 `READY` 방치라는 새 잔류 상태와 TX 1개를 추가한다. 그래서 단일 TX 안을 택했다.

### 남은 한계

TX A commit 직후 ⑤ 호출 전에 프로세스가 죽으면, **실제로는 PG 에 아무것도 보내지 않았는데도 미확인 시도로 남는다.** 이 상태에서 자동 재발송은 하지 않는다. 해소 경로는 reconcile 의 권위 조회이며, 조회가 0건이어도 그것만으로 실패로 확정하지 않고 미확인을 유지한다.

> **과제 근거** (5. callback·조회·회복 전략)
> "조회 0건·PENDING·조회 실패에는 미완료 상태를 유지합니다. 조회 한도 소진은 새 결제 허용 근거가 아닙니다."

> **과제 근거** (6. 결정적 검증과 제출)
> "발송 기록 뒤 재시작 → 저장된 시도·예약 유지, 미확인 시도 자동 재발송 0"

---

## 4. 같은 주문에 confirm 이 다시 올 때

### 4-1. 경우별 응답

`POST /api/v1/orders/{orderId}/confirm` 입력은 `paymentAttemptKey`, `cardType`, `cardNo` 이고 금액은 저장된 주문에서 결정한다. **모든 재요청은 PG 호출 0회다.**

| 경우 | 응답 | ErrorType |
|---|---|---|
| 사용자 미식별 | 401 | `UNAUTHORIZED` (기존) |
| 남의 주문 | 404, 존재를 알리지 않는다 | `NOT_FOUND` (기존, 현재 `OrderFacade.findOwnOrder` 와 동일) |
| 이전 POINT 확정 주문 (`CONFIRMED`, 시도 없음) | 409 "이미 확정된 주문입니다" | `CONFLICT` (기존) |
| 같은 키 + 같은 요청 + 진행 중 (`REQUESTED`·`PENDING`·`UNKNOWN`) | **202** + 주문·시도 상태 + reconcile 조회 경로 | 오류 아님 |
| 같은 키 + 같은 요청 + 종결 (`SUCCESS`·`FAILED`) | **200** + 저장된 결과 | 오류 아님 |
| 같은 키 + 다른 주문·금액·요청 | 409 | `CONFLICT` (기존) |
| 다른 키 + 이미 시도가 있는 주문 | 409 | `CONFLICT` (기존) |
| 처음 오는 `DRAFT` 주문 | **202** + 시도 생성 후 TX A 진행 | 오류 아님 |
| 입력 누락·형식 오류 (`paymentAttemptKey`·`cardType` 없음 등) | 400 | `BAD_REQUEST` (기존) |

**신규 ErrorType 은 필요하지 않다.** 네 가지 거절이 모두 기존 `ErrorType` 에 대응하고, `CoreException` 이 `customMessage` 를 받아 사유를 구분할 수 있다. 반면 202 와 200 을 같은 핸들러에서 나눠 반환하려면 `ResponseEntity<ApiResponse<T>>` 가 필요하다 (기존 컨트롤러는 `@ResponseStatus` 고정값만 쓴다).

> **과제 근거** (3. 기존 주문 확정에 PG 연결)
> "대기·결과 미확인인 confirm/reconcile 응답은 HTTP 202와 주문·시도 상태 및 조회 경로를 반환합니다. 같은 PG 시도의 완료 결과는 HTTP 200으로 조회·반환할 수 있습니다. 소유권·잘못된 입력·상충하는 시도는 기존 오류 계약에 맞춰 구분합니다."
> "같은 키의 다른 주문·금액·요청은 충돌입니다. 같은 주문에 새 키를 보내도 두 번째 PG 시도를 만들지 않습니다."
> "PG로 이미 종결한 같은 시도의 재요청은 저장 결과를 반환하며 추가 효과를 만들지 않습니다. 이전 POINT 확정 주문에는 기존 이미 확정된 상태 오류를 유지합니다."

### 4-2. 결정 ① 검사 순서 (확정)

**소유권 → 기존 시도 확인 → `DRAFT` 확인** 순서로 ①에서 판정한다.

이유: 같은 키 재요청이 오류가 아니라 202·200 을 받아야 하기 때문이다. 소유권을 가장 먼저 봐서, 남의 주문에 시도가 있다는 사실이 응답 차이로 새지 않는다.

검토한 대안: 소유권 → `DRAFT` → 기존 시도. 반례는 같은 키 재요청이다. 첫 confirm 으로 주문이 이미 `PAYMENT_PENDING` 이므로 `DRAFT` 검사에서 먼저 걸려 "이미 진행 중" 오류를 받는다. 과제가 요구한 "저장 결과를 반환하며 추가 효과를 만들지 않는다"를 지킬 수 없다.

### 4-3. 결정 ② attemptKey 유일 범위 (확정)

`UNIQUE(user_id, attempt_key)` 로 두고, `UNIQUE(order_id)` 는 별도로 유지한다.

이유: 다른 사용자와 키 문자열이 겹쳐도 충돌하지 않고, 남의 키 사용 여부가 응답으로 드러나지 않는다. `UNIQUE(order_id)` 는 "주문당 한 시도"를 담당하므로 역할이 다르고 함께 필요하다.

> **과제 근거** (1. 교체 범위와 상태 설계)
> "이번 범위는 주문당 한 결제 시도이며, 실패 후 새 결제 시도·환불·정산·사용자 취소·실제 카드 저장은 구현하지 않습니다."

### 4-4. 결정 ③ "같은 요청" 비교 기준 (확정)

**주문 ID + 금액 + `cardType`** 으로 비교한다. 카드 번호는 비교하지도, 저장하지도 않는다.

이유: 과제의 카드 번호 비영속 규칙 때문이다. 카드 번호만 다른 재요청은 "같은 요청"으로 통과하지만, 그 경로에서는 PG 를 다시 호출하지 않으므로 결제가 늘지 않는다.

검토한 대안

| 대안 | 문제 |
|---|---|
| 주문 ID + 금액만 | `cardType` 이 바뀐 재요청을 같은 요청으로 본다. 저장하는 필드로 구분할 수 있는데 굳이 버릴 이유가 없다 |
| 카드 번호 해시까지 포함 | 해시는 카드 번호에서 파생된 영속 데이터라 비영속 규칙과 충돌할 여지가 있다. 얻는 것은 "번호만 다른 재요청 거절"인데, 그 경로도 PG 호출이 0회라 실익이 없다 |

> **과제 근거** (3. 기존 주문 확정에 PG 연결)
> "시도에는 금액과 필요한 식별 관계만 보존하고 합성 카드 번호도 영속화하지 않습니다."

> **과제 근거** (2. 로컬 테스트 PG 계약과 adapter)
> "카드 값은 위와 같은 합성 값만 사용하고 DB·로그에 카드 번호를 남기지 않습니다."

### 4-5. 결정 ④ 진행 중 재요청 (확정)

저장된 상태만 반환한다. 202 + 주문·시도 상태 + reconcile 경로를 돌려주고 **PG 조회·호출을 0회** 한다.

이유: 외부 조회는 reconcile 의 역할이다. confirm 이 PG 를 건드리지 않으면 "confirm 재요청 N번에도 외부 호출은 1회"를 테스트로 바로 셀 수 있다.

> **과제 근거** (6. 결정적 검증과 제출)
> "같은 주문·시도의 동시 요청 → 시도·예약 각 1, 실제 create 1, 다른 키·불일치 요청은 효과 없이 거절"

> **과제 근거** (5. callback·조회·회복 전략)
> "콜백이 없어도 본인의 reconcile API로 기존 시도를 확인합니다."
> "create 자동 재시도는 하지 않습니다. 상시 스케줄러는 선택이며 수동 reconcile이 기본 복구 경로입니다."

### 4-6. 202·200 응답 본문 (확정)

> **과제 근거** (3. 기존 주문 확정에 PG 연결)
> "대기·결과 미확인인 confirm/reconcile 응답은 HTTP 202와 주문·시도 상태 및 조회 경로를 반환합니다. 같은 PG 시도의 완료 결과는 HTTP 200으로 조회·반환할 수 있습니다."

주문 상태, 시도 상태, 조회 경로 세 가지는 필수다.

**판단:** 조회 경로는 주문 조회(`GET /api/v1/orders/{orderId}`)와 reconcile(`POST /api/v1/orders/{orderId}/payment/reconcile`) 둘 다 담는다. 하나는 "상태 보기", 하나는 "결과 갱신 요청"으로 역할이 달라서, 호출자가 응답만 보고 다음 행동을 고를 수 있어야 하기 때문이다.

규칙

- `meta.result` 는 202 에서도 `SUCCESS` 다. 요청 처리에 성공했다는 뜻이고, 결제 결과는 `data` 가 표현한다.
- `transactionKey` 는 응답에 넣지 않는다. 외부 거래 키는 서버 안에서만 쓴다.
- 카드 번호는 절대 넣지 않는다.
- 200 응답(종결)도 같은 모양이다. 시도 상태가 `SUCCESS`·`FAILED`, 주문 상태가 `CONFIRMED`·`PAYMENT_FAILED` 로 바뀌어 담긴다.

예시: 처음 오는 `DRAFT` 주문의 confirm, HTTP 202. 기존 `ApiResponse<T>` 의 `meta`·`data` 형식을 그대로 따른다. `data` 안의 필드 이름은 신규 DTO 에서 정한다. create 응답이 `PENDING` 이라 시도 상태는 `PENDING` 이고, create 가 timeout 이었다면 `UNKNOWN` 으로 담긴다.

```json
{
  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
  "data": {
    "orderId": 42,
    "orderStatus": "PAYMENT_PENDING",
    "attemptStatus": "PENDING",
    "links": {
      "order": "/api/v1/orders/42",
      "reconcile": "/api/v1/orders/42/payment/reconcile"
    }
  }
}
```

---

## 5. PG 성공 뒤 내부 저장(TX B) 실패

### 5-1. 장면

PG 는 `SUCCESS` 인데, TX B 에서 ⑥⑦⑧ 을 반영하는 중 DB 오류가 나서 **TX B 전체가 rollback 된** 경우다.

rollback 뒤 상태는 TX B 에 들어가기 직전과 같다.

| 항목 | rollback 뒤 상태 |
|---|---|
| 결제 시도 | 미종결 그대로 (`REQUESTED`·`PENDING`·`UNKNOWN`) |
| 주문 | `PAYMENT_PENDING` |
| 예약 | `RESERVED` |
| 재고 | 변화 없음 (TX A 의 확보분만 반영된 상태) |

외부 PG 의 `PENDING` 은 내부 결제 시도에서도 `PENDING` 이다 (결정 ⑪, 8-1). 거래가 있다는 것은 확인됐지만 결과를 기다리는 상태이고, `REQUESTED`(아직 응답을 못 받음)·`UNKNOWN`(거래가 있는지 모름)과 구분한다.

> **과제 근거** (4. 재고 확보와 결과 반영)
> "최종 성공·실패 반영이 DB 오류로 rollback되면 동일한 외부 결과를 다시 조회해 적용할 수 있어야 합니다."

> **과제 근거** (6. 결정적 검증과 제출)
> "외부 성공 뒤 내부 저장 실패 → 내부 TX rollback, 같은 거래 재조회로 한 번 확정, create 추가가 0"

PG 에는 성공한 거래가 남아 있고 내부에는 아무 흔적이 없다. 이 어긋남을 **같은 외부 결과를 다시 조회해 TX B 를 다시 실행하는 것으로** 해소한다. 외부 거래를 새로 만들어 맞추지 않는다.

### 5-2. 지켜야 할 규칙

**⑥ 문지기, ⑦ 주문 상태, ⑧ 예약·재고는 반드시 하나의 TX B 안에 둔다.**

반례: ⑥만 먼저 commit 되고 ⑦에서 실패하면, 문지기가 이미 닫혀(`REQUESTED` → `SUCCESS`) 있어 재반영 시도가 영원히 0행이 된다. 결제 시도는 종결인데 주문은 `PAYMENT_PENDING` 에, 예약은 `RESERVED` 에 갇히고, 어떤 복구 경로로도 꺼낼 수 없다. 문지기를 통과한 실행이 ⑦⑧까지 끝내거나 아무것도 하지 않거나, 둘 중 하나여야 한다.

> **과제 근거** (4. 재고 확보와 결과 반영)
> "성공·반환 상태와 재고 변경을 같은 TX에서 보호해 중복 callback·동시 reconcile이 두 번 확정·반환하지 못하게 합니다."

**복구할 때 create 는 다시 보내지 않는다.** 같은 거래를 조회해서 TX B 를 다시 실행한다.

> **과제 근거** (3. 기존 주문 확정에 PG 연결)
> "발송 시작을 영속 기록한 뒤에는 자동으로 create를 다시 보내지 않습니다."

### 5-3. 결정 ⑤ 복구 경로 (확정)

**기본 복구 경로는 reconcile API(수동)다.** 스케줄러도 도입하되(아래 결정 ⑫), reconcile 과 **같은 서비스 메서드를 호출만** 한다.

**판단:** 과제가 수동 reconcile 을 기본 복구 경로로 지정했고, 반영 로직이 한곳에 있으면 TX B 문지기 하나로 중복이 막히기 때문이다.

> **과제 근거** (5. callback·조회·회복 전략)
> "create 자동 재시도는 하지 않습니다. 상시 스케줄러는 선택이며 수동 reconcile이 기본 복구 경로입니다."

조회 한도를 다 써도 **상태는 그대로 둔다.** `FAILED` 로 바꾸지 않고, 예약을 반환하지 않고, 새 결제를 만들지 않는다.

> **과제 근거** (5. callback·조회·회복 전략)
> "조회 0건·PENDING·조회 실패에는 미완료 상태를 유지합니다. 조회 한도 소진은 새 결제 허용 근거가 아닙니다."
> "OPEN 동안 실제 외부 조회가 차단돼도 결제 결과를 FAILED로 확정하지 않습니다."

#### 결정 ⑫ 스케줄러 세부 (확정)

전제: 스케줄러를 도입한다. 스케줄러는 reconcile 과 같은 서비스 메서드를 호출만 하고(결정 ⑤), 반영은 TX B 문지기가 맡는다.

**조회 한도.** 결제 시도에 조회 횟수(`PaymentAttempt.lookupCount`, **신규**)와 다음 조회 시각(`PaymentAttempt.nextLookupAt`, **신규**)을 저장하고, 간격을 늘려 가며 조회한다. 한도에 도달하면 "수동 확인 필요"(`PaymentAttempt.manualCheckRequired`, **신규**)로 표시하고 조회를 멈춘다. 결제 시도·주문 상태와 예약은 그대로 둔다. 한도·간격의 구체 숫자는 설정값으로 두고, 테스트 설정은 작게 한다.

> **과제 근거** (5. callback·조회·회복 전략)
> "제한된 재조회: 조회 횟수·간격·총 대기 예산을 정합니다. 자동 retry를 추가하면 조회에만 적용하고 실제 호출 수를 검증합니다."
> "조회 한도 소진은 새 결제 허용 근거가 아닙니다."

**판단:** 영원히 0건인 시도(TX A commit 직후 PG 호출 전에 서버가 죽은 경우, 3장 남은 한계)를 끝없이 조회하지 않기 위해서다.

기각: 한도 없이 매 주기 전부 조회. 끝없는 조회가 되고, 과제의 횟수·예산 요구를 채우지 못한다.

**서킷 OPEN.** 이번 주기를 통째로 건너뛰고 조회 횟수를 깎지 않는다. **실제로 PG 에 물어본 횟수만 센다.** (과제 근거는 위 인용 "OPEN 동안 실제 외부 조회가 차단돼도 결제 결과를 FAILED로 확정하지 않습니다.")

**판단:** PG 장애 동안 한도만 소진되어 회복 뒤 조회가 안 되는 일을 막는다.

기각: 차단된 것도 1회로 셈.

**다중 인스턴스.** 중복 실행을 허용한다. 반영은 TX B 문지기가 1회로 보장하고, 중복될 수 있는 것은 PG 조회만이다.

> **과제 근거** (6. 결정적 검증과 제출)
> "기존 로컬 사용자 식별·관리자 MockMvc 설정과 127.0.0.1 실행 범위를 유지합니다."

**판단:** 과제가 단일 실행 범위이므로 추가 도구 없이 둔다.

기각: ShedLock 등 실행 락. 이번 범위에서 과하다.

**구현 규칙.**

- 스케줄러 메서드 전체에 `@Transactional` 을 걸지 않는다. PG 조회가 TX 안에 들어가지 않고, 반영 TX 는 건별 TX B 하나다.
- 건별로 예외를 잡아 다음 건으로 넘어간다. 한 건의 실패가 나머지 조회를 막지 않는다.
- 마지막 변경 후 일정 시간이 지난 시도만 대상으로 한다. 막 생성·갱신된 시도를 create 응답·callback 과 경쟁하며 조회하지 않는다.

**남은 한계.** 다중 인스턴스로 운영하면 ShedLock 같은 실행 락이 필요하다. 지금은 PG 조회가 인스턴스 수만큼 중복될 수 있다.

### 5-4. 결정 ⑥ TX B 실패 시 응답 (확정)

| 경로 | 응답 |
|---|---|
| callback | 5xx |
| reconcile | 5xx |

**판단:** rollback 으로 DB 상태는 깨끗하므로, 응답은 반영에 실패했다는 사실을 정직하게 알린다. 로컬 테스트 PG 계약에 callback 재전송 규칙이 없어 PG 의 재전송에 기대지 않고, 복구는 reconcile 로 한다.

검토한 대안

| 대안 | 문제 |
|---|---|
| callback 에 200 응답 | 반영 실패를 숨긴다. PG 는 알림이 처리된 줄 알고, 내부에는 아무 기록도 없다 |
| reconcile 에 202 + 저장 상태 반환 | 호출자가 "아직 미확인이구나"로 읽는다. 실제로는 외부 결과를 알았는데 저장만 실패한 것이라 사실과 다르다 |

### 5-5. 상태 흐름

재고 5개인 상품을 수량 2개로 주문해 TX A 에서 확보를 마친 상태를 예로 든다. 가용 재고는 3개다.

| 시점 | 결제 시도 | 주문 | 예약 | 가용 재고 | create 호출 누적 |
|---|---|---|---|---|---|
| PG `SUCCESS` 확인 직후 (TX B 진입 전) | `REQUESTED`·`PENDING`·`UNKNOWN` 중 하나 | `PAYMENT_PENDING` | `RESERVED` | 3 | 1 |
| TX B 실패 후 (rollback) | 그대로 (`REQUESTED`·`PENDING`·`UNKNOWN`) | `PAYMENT_PENDING` | `RESERVED` | 3 | 1 (추가 0) |
| reconcile 1회 후 | `SUCCESS` | `CONFIRMED` + `paidAmount` | `CONSUMED` | 3 (추가 차감 없음) | 1 (추가 0) |
| 같은 결과로 callback·reconcile 이 또 와도 | `SUCCESS` 유지 | `CONFIRMED` 유지 | `CONSUMED` 유지 | 3 유지 | 1 (추가 0) |

마지막 행은 ⑥의 WHERE 가 미종결만 고르기 때문에 0행이 되어 ⑦⑧이 실행되지 않는 경우다. 성공 쪽에서는 재고가 다시 차감되지 않고, 실패 쪽에서는 예약이 두 번 반환되지 않는다.

> **과제 근거** (6. 결정적 검증과 제출)
> "재고 5개에서 수량 2개를 확보했다면 가용 재고는 3개입니다. PG 성공 후에도 3개이며, 확정 실패 후에는 5개입니다. 중복 실패 알림으로 7개가 되거나 UNKNOWN에서 5개로 돌아가면 잘못된 처리입니다."

---

## 6. 조율 위치 (확정)

| 진입점 | 조율 담당 | 하는 일 |
|---|---|---|
| `POST /api/v1/orders/{orderId}/confirm` | `OrderFacade` (기존) | TX A 실행 → ⑤ PG create 호출 → create 응답을 TX B 서비스에 전달 |
| `POST /api/v1/payments/callback` | `PaymentFacade` (신규) | PG 권위 조회·검증 → TX B 서비스에 전달 |
| `POST /api/v1/orders/{orderId}/payment/reconcile` | `PaymentFacade` (신규) | 본인 시도 확인 → PG 권위 조회·검증 → TX B 서비스에 전달 |
| 스케줄러 주기 실행 (HTTP 진입점 없음, 결정 ⑫) | `PaymentFacade` (신규) | 대상 시도 선별 → reconcile 과 같은 메서드 호출. 요청한 사용자가 없으므로 본인 시도 확인만 건너뛴다 |

reconcile 과 스케줄러는 `PaymentFacade` 의 같은 메서드(PG 권위 조회·검증 → TX B 전달)를 공유한다. 차이는 reconcile 이 그 앞에 본인 시도 확인을 한 번 더 하는 것뿐이다.

**판단:** 스케줄러 빈은 컨트롤러와 같은 "진입점"이므로 `interfaces` 계층에 두고 `PaymentFacade` 만 호출한다. 6장의 진입점 기준과 맞고, ArchUnit 의 interfaces → application 의존은 허용 방향이다.

두 Facade 는 **서로 호출하지 않는다.** 둘 다 공용 TX B 서비스 `PaymentResultService`(신규, 별도 `@Transactional` 빈)를 호출하고, ⑥⑦⑧은 그 안에서만 실행된다. reconcile 의 경로는 `/orders` 아래에 있지만 하는 일이 결제 복구이므로 `PaymentFacade` 가 맡는다.

**판단:** API 진입점과 역할 기준으로 나누면 Facade 의 책임이 이름과 맞고, Facade 간 호출이 생기지 않는다. confirm 은 주문을 확정하는 일이므로 `OrderFacade` 에, callback·reconcile 은 이미 시작된 결제의 결과를 맞추는 일이므로 `PaymentFacade` 에 둔다.

기각한 대안

| 대안 | 기각 이유 |
|---|---|
| `OrderFacade` 에 전부 | 주문 지휘자가 PG 의 결제 콜백까지 받는다. 주문과 무관한 외부 알림 검증 책임이 주문 Facade 에 섞인다 |
| `PaymentFacade` 에 전부 | 주문 확정의 위치가 주문 Facade 가 아니라 결제 Facade 가 되어 헷갈린다. confirm 이 주문 소유권·DRAFT 검사를 하려면 `OrderFacade` 를 부르게 되어 Facade → Facade 호출이 생길 수 있다 |

**ArchUnit 확인 결과: 걸리지 않는다.** 현재 `ArchitectureTest` 의 규칙은 세 개다.

| 규칙 | 이 구조에서의 적용 |
|---|---|
| domain 은 interfaces·application·infrastructure 에 의존하지 않는다 | `PaymentResultService` 가 쓰는 것은 모두 domain 의 Repository 인터페이스다. PG 포트도 domain 인터페이스 → infrastructure 구현으로 두면(4주차 `ProductListCache` 와 같은 방식) 위반이 없다 |
| application 은 interfaces·infrastructure 에 의존하지 않는다 | `OrderFacade`·`PaymentFacade` 는 domain 서비스·Repository 인터페이스·PG 포트만 호출한다 |
| interfaces 는 infrastructure 에 의존하지 않는다 | 컨트롤러는 Facade 만 호출한다 |

Facade 간 호출이나 같은 계층 안의 의존을 막는 규칙은 없다. 따라서 "두 Facade 는 서로 호출하지 않는다"는 ArchUnit 이 강제하는 규칙이 아니라 이 문서의 설계 약속이다. Checkstyle 도 `AvoidStarImport`·`UnusedImports` 두 가지뿐이라 패키지 간 import 를 제한하지 않는다.

**판단:** `PaymentResultService` 는 `domain/payment` 에 둔다. 기존 `OrderService`·`ProductService` 처럼 `@Transactional` 을 가진 domain 서비스 자리이고, 다른 도메인 패키지의 Repository(`OrderRepository`·`ProductRepository`)를 쓰는 것은 `LikeService` 가 `ProductRepository` 를 쓰는 기존 선례와 같다.

---

## 7. PG 조회 결과가 0건·2건 이상·불일치일 때

callback 과 reconcile 은 모두 PG 를 권위 조회한 뒤 TX B 에 들어간다(1장). 이 장은 그 조회 결과가 "정상 1건 일치"가 아닐 때 무엇을 하고 무엇을 하지 않는지 정한다.

### 7-1. 과제가 정한 것

**하지 않는 것 세 가지.** 결제 시도·주문 상태를 바꾸지 않는다. create 를 다시 보내지 않는다. 예약을 반환하지 않는다.

> **과제 근거** (4. 재고 확보와 결과 반영)
> "0건·2건 이상·금액/식별 불일치 → 미확인·충돌 보존, 재발송 금지, 임의 반환 없음"

> **과제 근거** (5. callback·조회·회복 전략)
> "다른 주문·금액·거래의 응답은 거절·충돌 기록하며 기존 상태를 바꾸지 않습니다."
> "조회 0건·PENDING·조회 실패에는 미완료 상태를 유지합니다. 조회 한도 소진은 새 결제 허용 근거가 아닙니다."

> **과제 근거** (6. 결정적 검증과 제출)
> "조회 0건·복수 거래·금액/주문 불일치 → 임의 성공·실패·재결제 없음, 미확인·충돌 보존"

**비교 항목 네 가지.** 조회 결과 한 건을 저장된 결제 시도와 아래 네 항목으로 대조한다. 하나라도 어긋나면 불일치다.

| 비교 항목 | PG 쪽 값 | 우리 쪽 값 |
|---|---|---|
| 제공자 계정 범위 | adapter 가 고정 헤더 `X-USER-ID: 135135` 로 조회한 결과인가 | adapter 설정값 (고객 입력이 아님) |
| 주문 | 응답의 `orderId` | `PaymentAttempt.providerOrderId` (1-5) |
| 거래 | 응답의 `transactionKey` | `PaymentAttempt.transactionKey` (저장돼 있을 때) |
| 금액 | 응답의 `amount` | `PaymentAttempt.amount` (= 주문의 `getTotalAmount()`) |

> **과제 근거** (5. callback·조회·회복 전략)
> "고정한 PG adapter의 조회 결과에서 제공자 계정 범위·주문·transactionKey·저장 금액을 확인한 뒤 적용합니다."

### 7-2. 결정 ⑦ 충돌 기록 (확정)

결제 시도의 **상태는 그대로 두고**, 시도 행에 충돌 표시·마지막 충돌 사유·충돌 시각을 기록한다. 이 기록은 ⑥ 문지기를 거치지 않는다. 상태 컬럼을 건드리지 않으므로 종결 여부와 무관하게 남길 수 있고, 이후 올바른 결과가 오면 ⑥이 평소처럼 통과한다.

**판단:** 과제의 "기존 상태를 바꾸지 않음"과 "충돌 기록"을 동시에 지킬 수 있고, 테스트에서 "충돌이 기록됐는가"를 행 하나로 확인할 수 있다.

기각한 대안

| 대안 | 기각 이유 |
|---|---|
| 상태를 `CONFLICT` 로 변경 | 상태 변경 금지를 어긴다. 이를 종결로 보면 이후 올바른 결과가 와도 ⑥ 문지기가 0행이 되어 영영 반영하지 못한다 |
| 별도 이력 테이블 | 이번 범위에서 과하다. 주문당 시도가 하나라 시도 행에 마지막 충돌만 남겨도 충분하다 |
| 로그만 남김 | 테스트로 검증할 수 없다 |

### 7-3. 결정 ⑧ 조회 경로 (확정)

결과 판단은 **항상 orderId 목록 조회**(`GET /api/v1/payments?orderId={providerOrderId}`)로 하고, **거래 개수까지 확인**한다.

**판단:** 테스트 PG 는 같은 orderId 의 중복 POST 를 걸러 주지 않는다. 숨은 재시도 등으로 거래가 2건 생긴 경우(이중 결제 의심)를 항상 검출하려는 보수적 선택이다.

> **과제 근거** (2. 로컬 테스트 PG 계약과 adapter)
> "테스트 PG는 호출자 멱등 키를 받지 않으며 같은 orderId의 새 POST를 중복 제거한다고 가정하지 않습니다."
> "GET /api/v1/payments?orderId={providerOrderId} → 같은 주문의 거래 목록 반환. 빈 목록도 표현"

기각한 대안: `transactionKey` 가 저장돼 있으면 단건 조회만 한다. 그 거래 하나는 확인되지만 같은 주문의 추가 거래를 영영 보지 못한다.

adapter 의 `transactionKey` 단건 조회(`GET /api/v1/payments/{transactionKey}`)는 과제 요구대로 구현하고 계약 테스트도 하되, **결과 판단에는 목록을 쓴다.**

> **과제 근거** (2. 로컬 테스트 PG 계약과 adapter)
> "query는 URI builder로 인코딩하고 transactionKey 조회를 연결합니다."

비용: 2건 이상이면 그중 우리 거래가 명확해 보여도 확정하지 않고 미확인 + 충돌로 둔다. 7-8 남은 한계에 기록한다.

### 7-4. 결정 ⑨ 0건과 저장된 key 의 구분 (확정)

| 상황 | 판정 |
|---|---|
| 목록이 0건 (reconcile·스케줄러 입구) | 단순 미확인. 충돌 아님 |
| 목록이 0건 (callback 입구) | 충돌 기록. callback 이 가져온 `transactionKey` 의 거래가 없다는 뜻이다 (결정 ⑬, 7-5) |
| 우리가 저장한 `transactionKey` 가 목록에 없음 (목록은 1건 이상) | 충돌 기록 |

**판단:** reconcile·스케줄러의 0건은 create 가 PG 에 닿지 않은 경우(3장 "호출 전 사망")에 정상적으로 생기는 상태다. 반면 PG 가 접수 key 를 발급해 줬는데 그 거래가 목록에 없다는 것은 이상 신호이므로 둘을 구분한다. callback 의 0건은 PG 가 보낸 key 자체가 목록에 없는 것이라 뒤쪽과 같은 성격이다.

### 7-5. 결정 ⑩ 불일치 callback 응답 (확정)

불일치(2건 이상·금액·주문·저장 key 누락)로 충돌을 기록한 callback 에는 **4xx 거절**로 응답한다. 오류 타입은 기존 `ErrorType.CONFLICT`(409) 를 쓴다. 신규 타입은 필요하지 않다.

**판단:** 과제 문구 "거절·충돌 기록"과 일치한다. 5장의 TX B 실패 5xx 와 구분되어, 응답 코드만으로 "내 쪽 저장 오류"(5xx)와 "보낸 쪽 내용 이상"(4xx)을 나눌 수 있다.

> **과제 근거** (6. 결정적 검증과 제출)
> "callback 경로도 실제 controller를 거쳐 검증하며, 조작한 callback 본문만으로 성공하지 않는지 확인합니다."

#### 결정 ⑬ 종결되지 않는 callback 응답 (확정)

시도가 종결되지 않는 세 경우는 성격이 서로 달라 응답 코드를 나눈다. 기준은 세 줄이다. **4xx** 는 보낸 쪽이 이상하다. **5xx** 는 우리가 처리·확인을 못 했다. **202** 는 우리는 정상 처리했고 PG 가 아직 진행 중이다.

| 경우 | callback 응답 | 판단 |
|---|---|---|
| 조회 0건 | 4xx 거절 (`ErrorType.CONFLICT`, 409) + 충돌 기록 (사유: 거래 없음) | callback 본문은 `transactionKey` 를 담고 온다. 그런데 목록에 거래가 0건이면 "존재하지 않는 거래를 알려 온 것"이다. reconcile 의 0건(단순 미확인, 7-4)과 성격이 다르고, 결정 ⑨의 "저장된 key 가 목록에 없으면 충돌"과 같은 성격이다 |
| 조회 실패 (timeout, 회로 OPEN) | 5xx | PG 에 확인하지 못했다. 5-4 의 "처리 못 했으면 정직하게 실패" 근거와 같다 |
| PG `PENDING` (1건, 네 항목 일치) | 202 + 저장 상태. 시도는 결정 ⑪대로 `PENDING` 전이 + key 저장 | 우리 쪽 처리는 정상으로 끝났고 PG 가 진행 중이다. reconcile 의 202 와 맞춘다 |

0건 거절의 과제 근거는 위 결정 ⑩의 인용("조작한 callback 본문만으로 성공하지 않는지 확인합니다")과 같다.

> **과제 근거** (3. 기존 주문 확정에 PG 연결)
> "대기·결과 미확인인 confirm/reconcile 응답은 HTTP 202와 주문·시도 상태 및 조회 경로를 반환합니다."

기각한 대안

| 대안 | 기각 이유 |
|---|---|
| 전부 202 | 조작된 callback 을 정상처럼 받아 주고, 조회 실패를 숨긴다 |
| 전부 5xx | 정상 처리한 `PENDING` 까지 실패로 응답한다 |

### 7-6. 경우별 표

조회 결과 한 가지에 대해 어디까지 바뀌는지 한 줄로 본다. "그대로"는 조회 직전 상태(`REQUESTED`·`PENDING`·`UNKNOWN`, `PAYMENT_PENDING`, `RESERVED`)를 유지한다는 뜻이다.

| 조회 결과 | 결제 시도 상태 | 충돌 기록 | 주문 | 예약·재고 | create 재발송 | callback 응답 |
|---|---|---|---|---|---|---|
| 1건, 네 항목 모두 일치, PG 상태 `SUCCESS`·`FAILED` | TX B ⑥에서 `SUCCESS`·`FAILED` 로 종결 | 없음 | ⑦ `CONFIRMED`·`PAYMENT_FAILED` | ⑧ 소비 / 반환 1회 | 없음 | 200 (TX B 실패 시 5xx, 5장) |
| 1건, 네 항목 모두 일치, PG 상태 `PENDING` | `PENDING` 으로 전이 + `transactionKey` 저장 (결정 ⑪, 8-1). 이미 `PENDING` 이면 그대로 | 없음 | 그대로 | 유지 | 없음 | 202 + 저장 상태 (결정 ⑬) |
| 0건 (reconcile·스케줄러 입구) | 그대로 | 없음 | 그대로 | 유지 | 없음 | 해당 없음 (reconcile 은 202 + 저장 상태) |
| 0건 (callback 입구) | 그대로 | 기록 (사유: 거래 없음) | 그대로 | 유지 | 없음 | 409 `CONFLICT` (결정 ⑬) |
| 2건 이상 | 그대로 | 기록 (사유: 복수 거래) | 그대로 | 유지 | 없음 | 409 `CONFLICT` |
| 금액 불일치 | 그대로 | 기록 (사유: 금액) | 그대로 | 유지 | 없음 | 409 `CONFLICT` |
| 주문(`providerOrderId`) 불일치 | 그대로 | 기록 (사유: 주문) | 그대로 | 유지 | 없음 | 409 `CONFLICT` |
| 저장된 `transactionKey` 가 목록에 없음 | 그대로 | 기록 (사유: 거래 누락) | 그대로 | 유지 | 없음 | 409 `CONFLICT` |
| 조회 실패 (timeout, 회로 OPEN) | `REQUESTED` 면 `UNKNOWN` 으로 기록 (1장 규칙), `PENDING` 이면 그대로, `UNKNOWN` 이면 그대로 | 없음 | 그대로 | 유지 | 없음 | 5xx (결정 ⑬) |

reconcile 입구도 같은 표를 따르되, 응답은 다르다. 과제가 reconcile 응답을 "저장된 상태와 확인 불가"로 정했으므로(5장 인용) 종결되지 않는 행(reconcile 입구의 0건, 조회 실패, `PENDING` 전이)은 202 + 저장 상태, 충돌 행은 409 다. callback 의 0건이 409 이고 조회 실패가 5xx 인 것과 다른 이유는 결정 ⑬의 판단 열에 있다.

### 7-7. 신규 이름

이 장에서 처음 나오는 이름이다. 0장 이름 대조표에도 같은 이름을 넣었다.

| 역할 | 이름 | 상태 |
|---|---|---|
| PG 조회 포트 | `PaymentGateway` (domain 인터페이스, infrastructure 구현) | **신규** |
| 목록 조회 | `PaymentGateway.findAllByOrderId(providerOrderId)` | **신규** |
| 단건 조회 | `PaymentGateway.findByTransactionKey(transactionKey)` | **신규** |
| 충돌 표시 | `PaymentAttempt.conflicted` | **신규** |
| 마지막 충돌 사유 | `PaymentAttempt.conflictReason` | **신규** |
| 충돌 시각 | `PaymentAttempt.conflictedAt` | **신규** |

충돌 표시 세 칸은 조회 결과뿐 아니라 create 응답에서도 쓴다. `orderId`·`amount` 가 불일치하는 422 가 같은 기준으로 충돌을 기록한다 (1-6 결정 ⑱). 실패 사유(`PaymentAttempt.failureReason`)는 1-6 에서 정의했다.

### 7-8. 남은 한계

같은 주문에 거래가 2건 이상이면, 그중 우리가 저장한 `transactionKey` 와 일치하는 거래가 명확히 있어도 확정하지 않는다. 미확인 + 충돌로 두고 사람 확인으로 넘긴다. 자동으로 풀 수 있는 경우를 일부 놓치는 대신, 이중 결제 의심 상황을 기계가 임의로 판단하지 않는다.

---

## 8. 상태 모델

### 8-1. 결제 시도 상태 전이

결제 시도 상태는 다섯 개다. `REQUESTED`, `PENDING`, `UNKNOWN`, `SUCCESS`, `FAILED`. 이 중 `PENDING` 은 결정 ⑪에서 새로 둔 내부 상태다 (**신규**, 0장 이름 대조표에도 반영했다).

> **과제 근거** (✅ Checklist)
> "생성 수락·처리 중·최종 성공·실패·결과 미확인을 구분한다."

#### 결정 ⑪ UNKNOWN → PENDING 전이 허용 (확정)

**장면.** create 가 timeout 으로 끝나 시도가 `UNKNOWN` 이 됐다. 그 뒤 reconcile 또는 callback 의 orderId 목록 조회에서 거래 1건이 네 항목(7-1) 모두 일치하고 PG 상태가 `PENDING` 으로 확인됐다.

**결정.** 시도를 `UNKNOWN` → `PENDING` 으로 바꾸고 `transactionKey` 를 저장한다.

> **과제 근거** (4. 재고 확보와 결과 반영)
> "생성 수락 PENDING → 결제 처리 중, 주문 PAYMENT_PENDING, 예약 유지"
> "timeout·응답 유실·조회 불가 → 결과 UNKNOWN, 주문 PAYMENT_PENDING, 예약 유지"

**판단:** "거래가 있는지 모름"(`UNKNOWN`)과 "거래는 있고 결과를 기다림"(`PENDING`)은 아는 정보가 다르므로 구분한다. `transactionKey` 가 저장되면 7-4 의 "저장된 key 가 목록에 없으면 충돌" 검사가 가능해진다.

기각한 대안

| 대안 | 기각 이유 |
|---|---|
| `UNKNOWN` 유지 + key 만 저장 | 사용자가 주문을 조회하면 실제(거래 있음, 결과 대기)와 다른 "결과 미확인"이 보인다 |
| 아무것도 바꾸지 않음 | key 가 없어 이후 충돌 검사(7-4)를 할 수 없다 |

주문(`PAYMENT_PENDING`)과 예약(`RESERVED`)은 `UNKNOWN` 과 `PENDING` 에서 같다. 달라지는 것은 결제 시도 상태 하나다.

#### 전이 표

| 지금 상태 | 다음 상태 | 계기 |
|---|---|---|
| `REQUESTED` | `PENDING` | create 응답 `PENDING`, 또는 조회로 거래 확인 |
| `REQUESTED` | `UNKNOWN` | create timeout·응답 유실 |
| `UNKNOWN` | `PENDING` | 조회로 거래 확인 (결정 ⑪) |
| `REQUESTED` | `FAILED` + 사유 `REJECTED_BEFORE_CREATE` | 효과 0건 확정 거절(`REJECTED_NO_EFFECT`). create 응답, TX B ⑥ (1-6) |
| `REQUESTED` / `PENDING` / `UNKNOWN` | `SUCCESS` / `FAILED` + 사유 `PG_FAILED` | 검증된 최종 결과. TX B ⑥ 문지기 |
| `SUCCESS` / `FAILED` | 없음 | 종결. 어떤 응답도 덮어쓰지 않는다 |

**금지 전이.** `PENDING` → `UNKNOWN`, 그리고 어떤 상태에서든 → `REQUESTED`.

> **과제 근거** (5. callback·조회·회복 전략)
> "종결 뒤 상충하는 응답은 덮어쓰지 않습니다. callback이 먼저 종결한 뒤 늦게 돌아온 생성 응답·timeout handler도 PENDING·UNKNOWN으로 되돌리지 않습니다."
> "조회 0건·PENDING·조회 실패에는 미완료 상태를 유지합니다."

금지 전이를 지키는 방법은 UPDATE 의 WHERE 다. `UNKNOWN` 기록은 `WHERE status = 'REQUESTED'` 로만 하고, `PENDING` 기록은 `WHERE status IN ('REQUESTED', 'UNKNOWN')` 으로, 종결 기록(⑥)은 `WHERE status IN ('REQUESTED', 'PENDING', 'UNKNOWN')` 으로 한다.

반례 두 가지

1. reconcile 이 시도를 `PENDING` 으로 바꾼 직후, 늦게 돌아온 create timeout 처리가 `UNKNOWN` 기록을 시도한다. `WHERE status = 'REQUESTED'` 에 걸려 0행이고 `PENDING` 이 유지된다.
2. `PENDING` 시도를 다시 조회했는데 timeout 이 났다. `UNKNOWN` 기록은 `REQUESTED` 에만 걸리므로 0행이고 `PENDING` 이 유지된다.

충돌 표시(7-2 의 `conflicted`·`conflictReason`·`conflictedAt`)는 상태와 별개 칸이다. 어떤 상태에서도 붙을 수 있고 상태를 바꾸지 않는다.

### 8-2. 주문 상태 전이

주문 상태는 네 개다. 기존 `DRAFT`·`CONFIRMED` 에 `PAYMENT_PENDING`·`PAYMENT_FAILED` 가 더해진다 (0장 대조표).

#### 전이 표

| 지금 상태 | 다음 상태 | 계기 |
|---|---|---|
| `DRAFT` | `PAYMENT_PENDING` | TX A ②. 잠긴 행에 일반 UPDATE |
| `PAYMENT_PENDING` | `CONFIRMED` + `paidAmount` | 검증된 `SUCCESS`. TX B ⑦ |
| `PAYMENT_PENDING` | `PAYMENT_FAILED` | 검증된 `FAILED` 또는 효과 0건 확정 거절. TX B ⑦ |
| `CONFIRMED` / `PAYMENT_FAILED` | 없음 | 종결 |

> **과제 근거** (4. 재고 확보와 결과 반영)
> "생성 수락 PENDING → 결제 처리 중, 주문 PAYMENT_PENDING 예약 유지"
> "검증한 SUCCESS → 결제 SUCCESS, 주문 CONFIRMED / 예약 소비, 추가 차감 없음"
> "검증한 FAILED 또는 효과 0건 확정 거절 → 결제 FAILED, 주문 PAYMENT_FAILED / 예약 한 번 반환"

**`PAYMENT_PENDING` 이 유지되는 경우.** 결제 시도가 `PENDING`·`UNKNOWN` 인 동안, 충돌이 기록된 동안(7-2), 조회 한도가 소진돼 "수동 확인 필요"로 표시된 동안(5-3 결정 ⑫) 모두 주문은 `PAYMENT_PENDING` 그대로다. 주문 상태를 바꾸는 것은 TX B ⑦뿐이고, ⑦은 검증된 최종 결과에서만 실행된다.

#### 결정 ⑭ PAYMENT_FAILED 는 종결 (확정)

`PAYMENT_FAILED` 에서 나가는 전이는 없다. 실패한 주문을 `DRAFT` 로 되돌려 다시 결제하게 하지 않는다.

> **과제 근거** (1. 교체 범위와 상태 설계)
> "이번 범위는 주문당 한 결제 시도이며, 실패 후 새 결제 시도·환불·정산·사용자 취소·실제 카드 저장은 구현하지 않습니다."

기각: `PAYMENT_FAILED` → `DRAFT` 복귀. `UNIQUE(order_id)`(TX A ④) 때문에 두 번째 결제 시도를 만들 수 없다. 되돌려도 결제할 수 없는 `DRAFT` 가 되어 상태가 거짓말을 한다.

#### 결정 ⑮ 결제수단 구분 (확정)

주문 상태는 공유한다. POINT 로 확정한 과거 주문도 PG 로 확정한 주문도 `CONFIRMED` 다. 대신 주문에 결제수단 값(`Order.paymentMethod`, **신규**. `POINT` / `PG`)을 둔다.

| 주문 | 결제수단 값 |
|---|---|
| 교체 전에 이미 `CONFIRMED` 인 주문 | `POINT` |
| 교체 전에 `DRAFT` 로 남아 있던 주문 | 비움(`null`). 아직 결제하지 않았다 |
| confirm 으로 TX A 에 들어가는 주문 | `PG` 로 기록 |

> **과제 근거** (1. 교체 범위와 상태 설계)
> "이전 포인트 결제 주문 → 기존 사실을 그대로 조회 / 결제수단 POINT·금액·결과·당시 품목 정보"
> "기존 DRAFT도 아직 결제하지 않았으므로 교체 후 시작하는 결제는 PG를 사용합니다. 이미 결제된 주문을 다시 처리하지 않습니다."

> **과제 근거** (3. 기존 주문 확정에 PG 연결)
> "GET /api/v1/orders/{orderId} → 본인 주문·결제수단·시도 상태·최종 결과 조회. 새 결제 요청을 만들지 않음"

**판단:** 상태와 결제수단은 서로 다른 축이다. 상태는 "어디까지 진행됐는가", 결제수단은 "무엇으로 결제했는가"다. 두 축을 하나의 enum 으로 합치면 상태 전이 표가 결제수단 수만큼 늘어난다.

기각한 대안

| 대안 | 기각 이유 |
|---|---|
| 상태를 분리 (`POINT_CONFIRMED` / `PG_CONFIRMED`) | 상태와 결제수단이라는 다른 개념이 한 enum 에 섞인다. "확정됐는가"를 묻는 모든 조건문이 값 두 개를 나열해야 한다 |
| 결제 시도 유무로 추론 | 결제 시도가 없는 주문이 POINT 로 보인다. 교체 전 `DRAFT` 주문은 결제 시도가 없지만 POINT 로 결제한 적도 없다 |

**기존 주문의 값 채우기 — 구현 시 확인.** 이 저장소에는 Flyway·Liquibase 같은 마이그레이션 도구가 없고, 스키마는 Hibernate `ddl-auto` 로 관리한다 (`modules/jpa/src/main/resources/jpa.yml`). local·test 프로필은 `ddl-auto: create` 라 실행마다 스키마를 다시 만들고, 그 외 프로필은 `none` 이다. 따라서 "교체 전 주문에 `POINT` 를 채운다"를 수행할 위치가 현재 코드에 없다. 과제 검증 환경(local·test)에서는 기존 데이터가 남지 않으므로 테스트에서 POINT 주문을 직접 만들어 보존을 확인하는 방식이 된다. 운영 데이터가 있는 환경의 채우기 방법은 구현 시 확인한다.

### 8-3. 예약 상태 전이

#### 전이 표

| 지금 상태 | 다음 상태 | 계기 | 재고 |
|---|---|---|---|
| `RESERVED` | `CONSUMED` | 검증된 `SUCCESS`. TX B ⑧ | 그대로. 추가 차감 없음 |
| `RESERVED` | `RELEASED` | 검증된 `FAILED` 또는 효과 0건 확정 거절. TX B ⑧ | `stock = stock + n` 을 **1회만** |
| `CONSUMED` / `RELEASED` | 없음 | 종결 | 변화 없음 |

재고를 되돌리는 것은 `RELEASED` 전이 한 번뿐이다. `WHERE status = 'RESERVED'` 가 1행일 때만 복원하므로(1-2 ⑧), 같은 실패 알림이 두 번 와도 두 번째는 0행이 되어 재고가 5 → 7 로 늘지 않는다.

> **과제 근거** (6. 결정적 검증과 제출)
> "재고 5개에서 수량 2개를 확보했다면 가용 재고는 3개입니다. PG 성공 후에도 3개이며, 확정 실패 후에는 5개입니다. 중복 실패 알림으로 7개가 되거나 UNKNOWN에서 5개로 돌아가면 잘못된 처리입니다."

**`RESERVED` 가 유지되는 경우.** create timeout, 조회 0건, 충돌 기록, 조회 한도 소진 모두 `RESERVED` 그대로다. 예약을 되돌릴 근거는 검증된 실패뿐이다.

> **과제 근거** (4. 재고 확보와 결과 반영)
> "timeout만으로 실패·예약 반환을 결정하지 않습니다."
> "0건·2건 이상·금액/식별 불일치 → 미확인·충돌 보존, 재발송 금지, 임의 반환 없음"

#### 결정 ⑯ 삭제된 상품의 예약 (확정)

상품 삭제는 **소프트 삭제**를 전제로 한다. 현재 코드가 이미 그렇다. `ProductService.deleteProduct` 가 `BaseEntity.delete()` 를 호출해 `deletedAt` 에 시각만 기록하고 행은 남기며, 조회·주문 경로는 `deletedAt IS NULL` 로 걸러 낸다.

실패 반환 시 재고는 `stock = stock + n` 으로 되돌리지만 `deletedAt` 은 건드리지 않는다. 그래서 반환이 삭제된 상품을 다시 노출시키지 않는다. 재고가 늘어나도 목록·상세·새 주문에서는 여전히 제외된다.

예약 중인 `PAYMENT_PENDING` 주문은 상품이 삭제돼도 저장된 품목·금액·예약으로 PG 결과를 마무리한다. TX B ⑦⑧은 `OrderItem` 의 스냅샷과 예약 행만 보고 동작하며, 상품의 `deletedAt` 을 조건으로 쓰지 않는다. 삭제 뒤 새로 시작하는 `DRAFT` 확정은 다르다. TX A ③의 `deductStockIfEnough` 가 `deletedAt IS NULL` 을 조건에 두므로 0행이 되어 거절된다.

> **과제 근거** (4. 재고 확보와 결과 반영)
> "삭제된 상품은 계속 새 조회·새 주문에서 제외합니다. 이미 재고 확보를 마친 PAYMENT_PENDING 주문은 저장된 품목·금액·예약으로 기존 PG 결과를 마무리합니다. 삭제 후 새로 시작하는 DRAFT 확정과 구분합니다."
> "실패 반환이 삭제 상품을 다시 노출하게 해서는 안 됩니다."

**판단:** 과제가 "물리 삭제가 미완료 예약의 참조·반환을 깨뜨린다면 종결까지 실제 행 제거를 미루고 비노출만 적용"하라고 조건부로 열어 두었지만, 현재 코드가 이미 소프트 삭제라 추가 장치가 필요하지 않다.

---

## 부록. 결정 필요

현재 결정 필요 항목 없음. 스케줄러 세부는 5-3 결정 ⑫, 종결되지 않는 callback 응답은 7-5 결정 ⑬으로 확정했다.
