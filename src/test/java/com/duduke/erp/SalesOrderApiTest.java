package com.duduke.erp;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 销售订单回归测试：主从表写入、金额汇总与更新语义。
 * <p>
 * 重点验证「更新是整体替换明细、而非追加」——若实现退化成追加，
 * 明细会翻倍、总额随之翻倍，这类缺陷只看接口是否返回 200 是发现不了的。
 */
class SalesOrderApiTest extends AbstractApiTest {

    private static final String PRODUCT_PATH = "/api/biz/products";
    private static final String CUSTOMER_PATH = "/api/biz/customers";
    private static final String ORDER_PATH = "/api/biz/sales_orders";

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private long createProduct(String token, String code) throws Exception {
        String body = """
                {"productCode":"%s","productName":"订单测试产品","unit":"台","unitPrice":100}
                """.formatted(code);
        String response = this.mockMvc.perform(post(PRODUCT_PATH)
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return this.readData(response).asLong();
    }

    private long createCustomer(String token, String code) throws Exception {
        String body = """
                {"customerCode":"%s","customerName":"订单测试客户"}
                """.formatted(code);
        String response = this.mockMvc.perform(post(CUSTOMER_PATH)
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return this.readData(response).asLong();
    }

    private String orderBody(String orderNo, long customerId, long productId,
                             double quantity, double unitPrice) {
        return """
                {"orderNo":"%s","customerId":%d,"orderDate":"2026-09-13",
                 "items":[{"productId":%d,"productName":"订单测试产品",
                           "quantity":%s,"unitPrice":%s}]}
                """.formatted(orderNo, customerId, productId, quantity, unitPrice);
    }

    @Test
    @DisplayName("创建订单：金额由服务端按数量 × 单价汇总")
    void createComputesTotal() throws Exception {
        String admin = token("admin");
        String sfx = suffix();
        long productId = createProduct(admin, "T-P-" + sfx);
        long customerId = createCustomer(admin, "T-C-" + sfx);
        long orderId = 0;
        try {
            String response = this.mockMvc.perform(post(ORDER_PATH)
                            .header("satoken", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody("T-SO-" + sfx, customerId, productId, 3, 100)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            orderId = this.readData(response).asLong();

            this.mockMvc.perform(get(ORDER_PATH + "/" + orderId).header("satoken", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalAmount").value(300))
                    .andExpect(jsonPath("$.data.items.length()").value(1));
        } finally {
            if (orderId > 0) {
                this.mockMvc.perform(delete(ORDER_PATH + "/" + orderId).header("satoken", admin));
            }
            this.mockMvc.perform(delete(CUSTOMER_PATH + "/" + customerId).header("satoken", admin));
            this.mockMvc.perform(delete(PRODUCT_PATH + "/" + productId).header("satoken", admin));
        }
    }

    @Test
    @DisplayName("PUT 更新：明细整体替换，不是追加")
    void updateReplacesItemsNotAppends() throws Exception {
        String admin = token("admin");
        String sfx = suffix();
        long productId = createProduct(admin, "T-P-" + sfx);
        long customerId = createCustomer(admin, "T-C-" + sfx);
        long orderId = 0;
        try {
            String response = this.mockMvc.perform(post(ORDER_PATH)
                            .header("satoken", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody("T-SO-" + sfx, customerId, productId, 3, 100)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            orderId = this.readData(response).asLong();

            // 改为 1 × 100：总额应为 100；若实现变成追加则为 400
            this.mockMvc.perform(put(ORDER_PATH + "/" + orderId)
                            .header("satoken", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody("T-SO-" + sfx, customerId, productId, 1, 100)))
                    .andExpect(status().isOk());

            this.mockMvc.perform(get(ORDER_PATH + "/" + orderId).header("satoken", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalAmount").value(100))
                    .andExpect(jsonPath("$.data.items.length()").value(1));
        } finally {
            if (orderId > 0) {
                this.mockMvc.perform(delete(ORDER_PATH + "/" + orderId).header("satoken", admin));
            }
            this.mockMvc.perform(delete(CUSTOMER_PATH + "/" + customerId).header("satoken", admin));
            this.mockMvc.perform(delete(PRODUCT_PATH + "/" + productId).header("satoken", admin));
        }
    }

    @Test
    @DisplayName("明细为空返回 400")
    void emptyItemsRejected() throws Exception {
        String admin = token("admin");
        String sfx = suffix();
        long customerId = createCustomer(admin, "T-C-" + sfx);
        try {
            this.mockMvc.perform(post(ORDER_PATH)
                            .header("satoken", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderNo":"T-SO-%s","customerId":%d,
                                     "orderDate":"2026-09-13","items":[]}""".formatted(sfx, customerId)))
                    .andExpect(status().isBadRequest());
        } finally {
            this.mockMvc.perform(delete(CUSTOMER_PATH + "/" + customerId).header("satoken", admin));
        }
    }

    @Test
    @DisplayName("客户不存在返回 400")
    void nonexistentCustomerRejected() throws Exception {
        String admin = token("admin");
        String sfx = suffix();
        long productId = createProduct(admin, "T-P-" + sfx);
        try {
            this.mockMvc.perform(post(ORDER_PATH)
                            .header("satoken", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody("T-SO-" + sfx, 99999999L, productId, 1, 100)))
                    .andExpect(status().isBadRequest());
        } finally {
            this.mockMvc.perform(delete(PRODUCT_PATH + "/" + productId).header("satoken", admin));
        }
    }

    @Test
    @DisplayName("只读账号不能创建订单（403）")
    void viewerCannotCreate() throws Exception {
        this.mockMvc.perform(post(ORDER_PATH)
                        .header("satoken", token("viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("T-SO-X", 1L, 1L, 1, 100)))
                .andExpect(status().isForbidden());
    }

}
