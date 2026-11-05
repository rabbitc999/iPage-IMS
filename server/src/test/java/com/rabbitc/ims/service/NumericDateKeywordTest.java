package com.rabbitc.ims.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitc.ipage.jdbc.ModelSession;
import com.rabbitc.ipage.reflect.ModelMeta;
import com.rabbitc.ims.enums.DataScope;
import com.rabbitc.ims.meta.MetaService;
import com.rabbitc.ims.meta.ModelRegistry;
import com.rabbitc.ims.model.Asset;
import com.rabbitc.ims.model.Department;
import com.rabbitc.ims.model.Employee;
import com.rabbitc.ims.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 关键字搜索要覆盖数字、日期列。
 * <p>
 * 金额、日期在库里是原生类型，直接 LIKE 会报类型错误或永远不命中；
 * 统一 CAST 成文本后，用户输入「123.45」「2024-09」「2024-09-01」都能查到。
 */
class NumericDateKeywordTest {

    private ModelSession sql;
    private CrudService crud;

    @BeforeEach
    void setup() throws Exception {
        String url = "jdbc:h2:mem:numeric_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        sql = ModelSession.sql(() -> DriverManager.getConnection(url));
        sql.synchronizeSchema(Department.class, Employee.class, Asset.class);

        ModelRegistry registry = mock(ModelRegistry.class);
        doReturn(Asset.class).when(registry).require("asset");
        when(registry.requireMeta("asset")).thenReturn(ModelMeta.of(Asset.class));
        AssociationAccess associations = new AssociationAccess(sql);
        MetaService meta = new MetaService(sql, registry, associations, null);
        crud = new CrudService(sql, registry, meta, mock(AuditService.class),
                new ObjectMapper(), List.of(), associations);
    }

    @Test
    void keywordMatchesNumericAndDateColumns() throws Exception {
        asset();

        assertEquals(1, search("123.45").total());
        assertEquals(1, search("2024-09").total());
        assertEquals(1, search("2024-09-01").total());
        assertEquals(0, search("999.99").total());
    }

    @Test
    void keywordStillMatchesTextAndAssociations() throws Exception {
        asset();

        assertEquals(1, search("A001").total());
        assertEquals(1, search("信息部").total());
        assertEquals(1, search("李四").total());
    }

    private PageResult search(String keyword) {
        return crud.page(admin(), "asset", Map.of("size", "10", "kw", keyword));
    }

    private LoginUser admin() {
        return new LoginUser("u", "admin", "管理员", null, 4, Set.<String>of(), DataScope.ALL, null, 0);
    }

    private void asset() throws Exception {
        Department department = new Department();
        department.code = "IT";
        department.name = "信息部";
        sql.insert(department);

        Employee keeper = new Employee();
        keeper.empNo = "E1001";
        keeper.name = "李四";
        keeper.deptId = department.id;
        sql.insert(keeper);

        Asset asset = new Asset();
        asset.code = "A001";
        asset.name = "显示器";
        asset.deptId = department.id;
        asset.keeperId = keeper.id;
        asset.price = new BigDecimal("123.45");
        asset.purchaseDate = LocalDate.of(2024, 9, 1);
        sql.insert(asset);
    }
}
