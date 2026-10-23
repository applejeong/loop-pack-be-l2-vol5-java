package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final UserRepository userRepository;
    private final ProductService productService;
    private final OrderService orderService;

    /**
     * 생성 시점에는 재고를 차감하지 않고, 상품의 현재 가격을 단가로 기록한다.
     */
    @Transactional
    public OrderInfo createOrder(Long userId, List<OrderCommand.Item> items) {
        requireIdentifiedUser(userId);

        List<OrderItem> orderItems = items.stream()
            .map(this::toOrderItem)
            .toList();

        return OrderInfo.from(orderService.createOrder(userId, orderItems));
    }

    /**
     * 주문 확정 = PG 결제 시작. 포인트는 차감하지 않는다.
     * 이 메서드는 트랜잭션을 갖지 않는다. PG 호출 동안 DB 트랜잭션·락을 보유하지 않기 위해 세 구간으로 나눈다 (설계 1장, 6장).
     * 사용자 식별(401) → 합성 카드 입력 검사(400) → TX A(PaymentStartService.start)
     * → STARTED 면 TX 밖에서 PG create 1회 → 관찰 결과를 TX B(PaymentResultService)에 전달
     *   (ACCEPTED → recordPending, UNKNOWN → recordUnknown, REJECTED_NO_EFFECT → applyFinal FAILED/REJECTED_BEFORE_CREATE)
     * → EXISTING 이면 PG 호출 없이 저장된 상태만 반환
     * → 읽기 트랜잭션 안에서 주문·시도를 다시 읽어 응답을 만든다.
     */
    public OrderConfirmInfo confirmOrder(Long requesterId, Long orderId, OrderCommand.Confirm command) {
        throw new UnsupportedOperationException("1단계 미구현");
    }

    @Transactional(readOnly = true)
    public List<OrderInfo> getOrders(Long userId) {
        requireIdentifiedUser(userId);

        return orderService.getOrders(userId).stream()
            .map(OrderInfo::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrder(Long requesterId, Long orderId) {
        return OrderInfo.from(findOwnOrder(requesterId, orderId));
    }

    /**
     * 관리자 조회. ROLE_ADMIN 검사가 접근 경계이므로 소유권을 확인하지 않는다.
     */
    @Transactional(readOnly = true)
    public List<OrderInfo> getOrdersForAdmin(Long userId, int page, int size) {
        return orderService.getOrdersForAdmin(userId, page, size).stream()
            .map(OrderInfo::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrderForAdmin(Long orderId) {
        return OrderInfo.from(orderService.getOrder(orderId));
    }

    private OrderItem toOrderItem(OrderCommand.Item item) {
        Product product = productService.getActiveProduct(item.productId());
        return new OrderItem(item.productId(), item.quantity(), product.getPrice().getAmount());
    }

    /**
     * 남의 주문은 존재를 알리지 않고 없는 것으로 응답한다.
     */
    private Order findOwnOrder(Long requesterId, Long orderId) {
        requireIdentifiedUser(requesterId);

        Order order = orderService.getOrder(orderId);
        if (!order.isOwnedBy(requesterId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다.");
        }
        return order;
    }

    private void requireIdentifiedUser(Long userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "사용자 식별에 실패했습니다.");
        }
    }
}
