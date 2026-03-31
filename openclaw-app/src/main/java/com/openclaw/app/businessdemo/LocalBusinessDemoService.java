package com.openclaw.app.businessdemo;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 最小本地业务服务样板。
 * 用于演示：Skill 负责描述流程，真正的业务能力通过 AgentTool 调用本地
 * Java 方法，而不是把业务逻辑直接写进 SKILL.md。
 */
@Service
public class LocalBusinessDemoService {

    private final Map<String, CustomerProfile> customers = Map.of(
            "CUST-1001", new CustomerProfile("CUST-1001", "Acme Trading", "vip", "active"),
            "CUST-1002", new CustomerProfile("CUST-1002", "Blue River Studio", "standard", "pending"));

    private final Map<String, OrderSummary> orders = Map.of(
            "ORD-9001", new OrderSummary("ORD-9001", "CUST-1001", "paid", "shipped", 1299.00, "CN-SH"),
            "ORD-9002", new OrderSummary("ORD-9002", "CUST-1002", "unpaid", "processing", 299.00, "CN-HZ"));

    public Optional<CustomerProfile> findCustomer(String customerId) {
        return Optional.ofNullable(customers.get(normalizeId(customerId)));
    }

    public Optional<OrderSummary> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(normalizeId(orderId)));
    }

    public Optional<Map<String, Object>> buildOrderOverview(String orderId) {
        Optional<OrderSummary> orderOpt = findOrder(orderId);
        if (orderOpt.isEmpty()) {
            return Optional.empty();
        }

        OrderSummary order = orderOpt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order.toMap());
        findCustomer(order.customerId()).ifPresent(customer -> result.put("customer", customer.toMap()));
        result.put("recommendedNextAction", recommendNextAction(order));
        return Optional.of(result);
    }

    private String normalizeId(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase();
    }

    private String recommendNextAction(OrderSummary order) {
        if (!"paid".equalsIgnoreCase(order.paymentStatus())) {
            return "请联系财务，或提醒客户先完成付款，再继续安排发货。";
        }
        if (!"shipped".equalsIgnoreCase(order.fulfillmentStatus())) {
            return "请协调运营完成履约，并同步更新物流节点信息。";
        }
        return "可以把发货进展同步给客户，并持续关注后续配送异常。";
    }

    public record CustomerProfile(String customerId, String name, String tier, String accountStatus) {
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("customerId", customerId);
            result.put("name", name);
            result.put("tier", tier);
            result.put("accountStatus", accountStatus);
            return result;
        }
    }

    public record OrderSummary(
            String orderId,
            String customerId,
            String paymentStatus,
            String fulfillmentStatus,
            double amount,
            String warehouseCode) {
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderId", orderId);
            result.put("customerId", customerId);
            result.put("paymentStatus", paymentStatus);
            result.put("fulfillmentStatus", fulfillmentStatus);
            result.put("amount", amount);
            result.put("warehouseCode", warehouseCode);
            return result;
        }
    }
}
