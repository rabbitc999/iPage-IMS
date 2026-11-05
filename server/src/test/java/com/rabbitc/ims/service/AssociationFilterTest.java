package com.rabbitc.ims.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitc.ipage.jdbc.ModelSession;
import com.rabbitc.ipage.reflect.ModelMeta;
import com.rabbitc.ims.enums.DataScope;
import com.rabbitc.ims.enums.RecordStatus;
import com.rabbitc.ims.meta.MetaService;
import com.rabbitc.ims.meta.ModelRegistry;
import com.rabbitc.ims.model.Department;
import com.rabbitc.ims.model.Ticket;
import com.rabbitc.ims.model.User;
import com.rabbitc.ims.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证外键字段可以按关联记录的显示名称过滤。
 * <p>
 * 页面上的「处理人」是 {@code assigneeId}（存 UUID），用户输入的是姓名。
 * 若直接把姓名拼进 {@code assignee_id LIKE ?}，永远查不到；本测试守住
 * 「先解析姓名到主键，再用 IN 过滤」的行为。
 */
class AssociationFilterTest {

    private ModelSession sql;
    private CrudService crud;

    @BeforeEach
    void setup() throws Exception {
        String url = "jdbc:h2:mem:association_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        sql = ModelSession.sql(() -> DriverManager.getConnection(url));
        sql.synchronizeSchema(Department.class, User.class, Ticket.class);

        ModelRegistry registry = mock(ModelRegistry.class);
        doReturn(Ticket.class).when(registry).require("ticket");
        when(registry.requireMeta("ticket")).thenReturn(ModelMeta.of(Ticket.class));
        AssociationAccess associations = new AssociationAccess(sql);
        MetaService meta = new MetaService(sql, registry, associations, null);
        crud = new CrudService(sql, registry, meta, mock(AuditService.class),
                new ObjectMapper(), List.of(), associations);
    }

    @Test
    void filtersForeignKeyByRelatedDisplayName() throws Exception {
        Department department = department();
        User assignee = assignee();
        Ticket ticket = ticket(department, assignee);
        sql.insert(ticket);

        PageResult result = crud.page(admin(), "ticket", Map.of(
                "size", "10",
                "f_assigneeId", "张三",
                "op_assigneeId", "LIKE"));

        assertEquals(1, result.total());
        assertEquals(assignee.id, result.rows().get(0).get("assigneeId"));
    }

    /** 名字对不上时必须是空集，不能因为忽略条件而退化成全表。 */
    @Test
    void unmatchedNameReturnsEmptyPage() throws Exception {
        Department department = department();
        User assignee = assignee();
        sql.insert(ticket(department, assignee));

        PageResult result = crud.page(admin(), "ticket", Map.of(
                "size", "10",
                "f_assigneeId", "不存在的人",
                "op_assigneeId", "LIKE"));

        assertEquals(0, result.total());
    }

    /** 关键字搜索也要能命中关联记录的显示名称，而不只是本表字符串列。 */
    @Test
    void keywordSearchMatchesRelatedDisplayName() throws Exception {
        Department department = department();
        User assignee = assignee();
        sql.insert(ticket(department, assignee));

        PageResult result = crud.page(admin(), "ticket", Map.of("size", "10", "kw", "张三"));

        assertEquals(1, result.total());
        assertEquals(assignee.id, result.rows().get(0).get("assigneeId"));
    }

    /** 枚举筛选兼容中文标签与常量名，避免下拉显示「待处理」而库里存 OPEN 时对不上。 */
    @Test
    void filtersEnumByLabelOrName() throws Exception {
        Department department = department();
        User assignee = assignee();
        sql.insert(ticket(department, assignee));

        assertEquals(1, crud.page(admin(), "ticket", Map.of(
                "size", "10", "f_status", "待处理", "op_status", "EQ")).total());
        assertEquals(1, crud.page(admin(), "ticket", Map.of(
                "size", "10", "f_status", "OPEN", "op_status", "EQ")).total());
        assertEquals(0, crud.page(admin(), "ticket", Map.of(
                "size", "10", "f_status", "处理中", "op_status", "EQ")).total());
    }

    /** 「包含 处理」应同时命中「待处理」「处理中」的常量名，而不是拿中文去 LIKE 英文列。 */
    @Test
    void fuzzyEnumFilterMatchesChineseLabel() throws Exception {
        Department department = department();
        User assignee = assignee();
        sql.insert(ticket(department, assignee));

        assertEquals(1, crud.page(admin(), "ticket", Map.of(
                "size", "10", "f_status", "处理", "op_status", "LIKE")).total());
    }

    /** 关键字搜索命中枚举中文标签或常量名。 */
    @Test
    void keywordSearchMatchesEnumLabelOrName() throws Exception {
        Department department = department();
        User assignee = assignee();
        sql.insert(ticket(department, assignee));

        assertEquals(1, crud.page(admin(), "ticket", Map.of("size", "10", "kw", "待处理")).total());
        assertEquals(1, crud.page(admin(), "ticket", Map.of("size", "10", "kw", "OPEN")).total());
    }

    private LoginUser admin() {
        return new LoginUser("u", "admin", "管理员", null, 4, Set.<String>of(), DataScope.ALL, null, 0);
    }

    private Department department() throws Exception {
        Department department = new Department();
        department.code = "IT";
        department.name = "信息部";
        sql.insert(department);
        return department;
    }

    private User assignee() throws Exception {
        User user = new User();
        user.username = "zhangsan";
        user.name = "张三";
        user.status = RecordStatus.ENABLED;
        sql.insert(user);
        return user;
    }

    private Ticket ticket(Department department, User assignee) {
        Ticket ticket = new Ticket();
        ticket.title = "打印机无法连接";
        ticket.deptId = department.id;
        ticket.assigneeId = assignee.id;
        return ticket;
    }
}
