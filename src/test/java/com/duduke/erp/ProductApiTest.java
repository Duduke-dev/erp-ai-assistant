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
 * 产品接口回归测试：CRUD、REST 语义与写操作鉴权。
 * <p>
 * 编码带 UUID 后缀，避免重复运行或与既有数据冲突；每个用例自行清理所建数据。
 */
class ProductApiTest extends AbstractApiTest {

    private static final String PATH = "/api/biz/products";

    private String uniqueCode() {
        return "T-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 新增产品并返回主键 */
    private long createProduct(String token, String code, String name) throws Exception {
        String body = """
                {"productCode":"%s","productName":"%s","unit":"台","unitPrice":100}
                """.formatted(code, name);
        String response = this.mockMvc.perform(post(PATH)
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return this.readData(response).asLong();
    }

    @Test
    @DisplayName("POST 新增后可按 id 查回")
    void createThenGet() throws Exception {
        String admin = token("admin");
        String code = uniqueCode();
        long id = createProduct(admin, code, "集成测试产品");
        try {
            this.mockMvc.perform(get(PATH + "/" + id).header("satoken", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.productCode").value(code))
                    .andExpect(jsonPath("$.data.productName").value("集成测试产品"));
        } finally {
            this.mockMvc.perform(delete(PATH + "/" + id).header("satoken", admin));
        }
    }

    @Test
    @DisplayName("编码重复返回 400")
    void duplicateCodeRejected() throws Exception {
        String admin = token("admin");
        String code = uniqueCode();
        long id = createProduct(admin, code, "重复编码测试");
        try {
            this.mockMvc.perform(post(PATH)
                            .header("satoken", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"productCode":"%s","productName":"另一个"}""".formatted(code)))
                    .andExpect(status().isBadRequest());
        } finally {
            this.mockMvc.perform(delete(PATH + "/" + id).header("satoken", admin));
        }
    }

    @Test
    @DisplayName("PUT 全量更新生效")
    void updateApplies() throws Exception {
        String admin = token("admin");
        String code = uniqueCode();
        long id = createProduct(admin, code, "更新前");
        try {
            this.mockMvc.perform(put(PATH + "/" + id)
                            .header("satoken", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"productCode":"%s","productName":"更新后","unit":"箱","unitPrice":888}"""
                                    .formatted(code)))
                    .andExpect(status().isOk());

            this.mockMvc.perform(get(PATH + "/" + id).header("satoken", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.productName").value("更新后"))
                    .andExpect(jsonPath("$.data.unit").value("箱"))
                    .andExpect(jsonPath("$.data.unitPrice").value(888));
        } finally {
            this.mockMvc.perform(delete(PATH + "/" + id).header("satoken", admin));
        }
    }

    @Test
    @DisplayName("更新不存在的资源返回 400")
    void updateMissingReturns400() throws Exception {
        this.mockMvc.perform(put(PATH + "/99999999")
                        .header("satoken", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"X","productName":"X"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("删除后不可再查到")
    void deleteRemoves() throws Exception {
        String admin = token("admin");
        long id = createProduct(admin, uniqueCode(), "待删除");
        this.mockMvc.perform(delete(PATH + "/" + id).header("satoken", admin))
                .andExpect(status().isOk());
        this.mockMvc.perform(get(PATH + "/" + id).header("satoken", admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("只读账号的写操作全部 403")
    void readOnlyAccountCannotWrite() throws Exception {
        String viewer = token("viewer");
        String body = """
                {"productCode":"X","productName":"X"}""";

        this.mockMvc.perform(post(PATH).header("satoken", viewer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        this.mockMvc.perform(put(PATH + "/1").header("satoken", viewer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        this.mockMvc.perform(delete(PATH + "/1").header("satoken", viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("分页查询返回 total 与 records（路径不带 /page）")
    void pageReturnsPagedResult() throws Exception {
        this.mockMvc.perform(get(PATH + "?pageNo=1&pageSize=5").header("satoken", token("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").exists())
                .andExpect(jsonPath("$.data.records").isArray());
    }

}
