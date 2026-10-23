package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentCreateCommand;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentLookupResult;
import com.loopers.domain.payment.PaymentTransaction;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 로컬 테스트 PG 의 실제 HTTP adapter (설계 1-6 결정 ㉑~㉖, 7-3).
 * RestClient + JDK HttpClient(JdkClientHttpRequestFactory) 를 이 클래스 안에서 조립한다. 프록시를 쓰지 않고, HTTP/1.1 로 고정하며,
 * 연결 timeout 은 HttpClient 에, 응답 timeout 은 request factory 에 설정값으로 건다. 어떤 요청도 다시 보내지 않는다.
 * base URL·X-USER-ID·callback URL 은 설정값만 쓴다. HTTP 요청·응답 본문은 로그에 남기지 않는다 (요청 본문에 카드 번호가 있다).
 * 응답은 상태 코드와 본문 문자열로 받아 트리로 해석한다. transactionCreated 가 진짜 boolean 인지 보기 위해서다.
 * create: 202 + PENDING → ACCEPTED, 422 + 정확한 code + boolean false + orderId·amount 일치 → REJECTED_NO_EFFECT,
 * 202·422 인데 orderId·amount 불일치 → UNKNOWN + 충돌 사유(key 없음), 그 밖의 응답·잘못된 JSON·timeout·연결 실패 → UNKNOWN.
 * 조회(목록·단건): 200 → FOUND + 거래, 단건 404 → FOUND + 0건, 그 밖의 응답·잘못된 JSON·timeout·연결 실패 → UNAVAILABLE.
 * enabled=false 면 세 메서드 모두 IllegalStateException 을 던지고 서버에 요청을 보내지 않는다.
 */
@Component
public class HttpPaymentGateway implements PaymentGateway {

    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final String REJECTED_BEFORE_CREATE_CODE = "TEST_PG_REJECTED_BEFORE_CREATE";
    private static final int ACCEPTED = 202;
    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final int UNPROCESSABLE_CONTENT = 422;

    /**
     * 상태 코드와 본문 문자열. 본문이 없으면 빈 문자열이다.
     */
    private record RawResponse(int status, String body) {}

    private final PaymentGatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public HttpPaymentGateway(PaymentGatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(properties.connectTimeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
            .baseUrl(properties.baseUrl())
            .defaultHeader(USER_ID_HEADER, properties.userId())
            .requestFactory(requestFactory)
            .build();
    }

    /**
     * POST /api/v1/payments. 본문은 orderId·cardType·cardNo·amount(정수)·callbackUrl(설정값). 1회만 보낸다.
     * 본문은 메시지 컨버터를 거치지 않고 바이트로 직접 쓴다. 컨버터 경로는 RestClient 가 DEBUG 레벨에서 본문 전체를 로그에 남기기 때문이다.
     */
    @Override
    public PaymentCreateResult create(PaymentCreateCommand command) {
        requireEnabled();
        byte[] body = createBody(command);
        RawResponse response;
        try {
            response = restClient.post()
                .uri(PAYMENTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body((StreamingHttpOutputMessage.Body) outputStream -> outputStream.write(body))
                .exchange(HttpPaymentGateway::readRaw);
        } catch (RestClientException e) {
            return PaymentCreateResult.unknown();
        }
        return mapCreate(command, response);
    }

    /**
     * GET /api/v1/payments?orderId={providerOrderId}. 쿼리는 URI builder 가 인코딩한다.
     */
    @Override
    public PaymentLookupResult findAllByOrderId(String providerOrderId) {
        requireEnabled();
        RawResponse response;
        try {
            response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(PAYMENTS_PATH).queryParam("orderId", providerOrderId).build())
                .exchange(HttpPaymentGateway::readRaw);
        } catch (RestClientException e) {
            return PaymentLookupResult.unavailable();
        }
        if (response.status() != OK) {
            return PaymentLookupResult.unavailable();
        }
        try {
            JsonNode body = objectMapper.readTree(response.body());
            if (!body.isArray()) {
                return PaymentLookupResult.unavailable();
            }
            List<PaymentTransaction> transactions = new ArrayList<>();
            for (JsonNode node : body) {
                transactions.add(toTransaction(node));
            }
            return PaymentLookupResult.found(transactions);
        } catch (IOException | IllegalArgumentException e) {
            return PaymentLookupResult.unavailable();
        }
    }

    /**
     * GET /api/v1/payments/{transactionKey}. 200 → FOUND + 1건, 404 → FOUND + 0건, 그 밖의 실패 → UNAVAILABLE.
     */
    @Override
    public PaymentLookupResult findByTransactionKey(String transactionKey) {
        requireEnabled();
        RawResponse response;
        try {
            response = restClient.get()
                .uri(PAYMENTS_PATH + "/{transactionKey}", transactionKey)
                .exchange(HttpPaymentGateway::readRaw);
        } catch (RestClientException e) {
            return PaymentLookupResult.unavailable();
        }
        if (response.status() == NOT_FOUND) {
            return PaymentLookupResult.found(List.of());
        }
        if (response.status() != OK) {
            return PaymentLookupResult.unavailable();
        }
        try {
            return PaymentLookupResult.found(List.of(toTransaction(objectMapper.readTree(response.body()))));
        } catch (IOException | IllegalArgumentException e) {
            return PaymentLookupResult.unavailable();
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new IllegalStateException(
                "PG adapter 가 비활성화돼 있다 (payment.pg.enabled=false). 테스트라면 FakePaymentGatewayConfig 를 @Import 했는지 확인한다.");
        }
    }

    private byte[] createBody(PaymentCreateCommand command) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("orderId", command.providerOrderId());
        body.put("cardType", command.cardType());
        body.put("cardNo", command.cardNo());
        body.put("amount", command.amount());
        body.put("callbackUrl", properties.callbackUrl());
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 202 는 접수, 422 는 네 조건을 모두 갖춘 경우만 거절이다. 두 경우 모두 orderId·amount 가 요청과 다르면 충돌 사유를 붙인 UNKNOWN 이다.
     * 본문이 없거나 JSON 이 아니거나 그 밖의 상태 코드면 증거가 아니므로 UNKNOWN 이다.
     */
    private PaymentCreateResult mapCreate(PaymentCreateCommand command, RawResponse response) {
        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (IOException e) {
            return PaymentCreateResult.unknown();
        }
        if (body == null || !body.isObject()) {
            return PaymentCreateResult.unknown();
        }
        if (response.status() == ACCEPTED) {
            PaymentAttempt.ConflictReason conflict = conflictOf(command, body);
            if (conflict != null) {
                return PaymentCreateResult.unknownWithConflict(conflict);
            }
            String transactionKey = textOf(body, "transactionKey");
            if (transactionKey == null || !PaymentTransaction.Status.PENDING.name().equals(textOf(body, "status"))) {
                return PaymentCreateResult.unknown();
            }
            return PaymentCreateResult.accepted(transactionKey);
        }
        if (response.status() == UNPROCESSABLE_CONTENT) {
            JsonNode transactionCreated = body.get("transactionCreated");
            boolean rejectedBeforeCreate = REJECTED_BEFORE_CREATE_CODE.equals(textOf(body, "code"))
                && transactionCreated != null && transactionCreated.isBoolean() && !transactionCreated.booleanValue();
            if (!rejectedBeforeCreate) {
                return PaymentCreateResult.unknown();
            }
            PaymentAttempt.ConflictReason conflict = conflictOf(command, body);
            if (conflict != null) {
                return PaymentCreateResult.unknownWithConflict(conflict);
            }
            return PaymentCreateResult.rejectedNoEffect();
        }
        return PaymentCreateResult.unknown();
    }

    /**
     * 응답의 orderId·amount 가 요청과 다르면 충돌 사유. 값이 없거나 형식이 다르면 비교하지 않고 null (증거 아님으로만 처리).
     */
    private static PaymentAttempt.ConflictReason conflictOf(PaymentCreateCommand command, JsonNode body) {
        String orderId = textOf(body, "orderId");
        if (orderId != null && !orderId.equals(command.providerOrderId())) {
            return PaymentAttempt.ConflictReason.ORDER_MISMATCH;
        }
        JsonNode amount = body.get("amount");
        if (amount != null && amount.isIntegralNumber() && amount.asLong() != command.amount()) {
            return PaymentAttempt.ConflictReason.AMOUNT_MISMATCH;
        }
        return null;
    }

    private static PaymentTransaction toTransaction(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("거래 응답이 객체가 아니다.");
        }
        String transactionKey = textOf(node, "transactionKey");
        String orderId = textOf(node, "orderId");
        String status = textOf(node, "status");
        JsonNode amount = node.get("amount");
        if (transactionKey == null || orderId == null || status == null || amount == null || !amount.isIntegralNumber()) {
            throw new IllegalArgumentException("거래 응답에 필수 값이 없다.");
        }
        return new PaymentTransaction(transactionKey, orderId, amount.asLong(), PaymentTransaction.Status.valueOf(status));
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static RawResponse readRaw(HttpRequest request, ClientHttpResponse response) throws IOException {
        return new RawResponse(
            response.getStatusCode().value(), new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
    }
}
