package com.minimall.contract;

import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysUserRepository;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.service.UserService;
import com.minimall.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台管理端的**正向 CRUD 环**(真 HTTP、真库)。
 *
 * <p><b>为什么需要这个类</b>:此前的 HTTP 用例只有两类 —— 权限矩阵(断言"不该通过的被拒绝了")
 * 与认证流程。它们对控制器层的覆盖有个共同的空洞:**请求体不合法时,校验阶段就 400 了,
 * 控制器方法一行都不会执行**。所以几十个写接口从来没被真正调用过,覆盖率停在 40% 上下。
 *
 * <p>这里逐个走完 create → update → 状态/授权 → delete,把每个控制器的写方法真正执行一遍。
 * 它顺带钉住了一批只有真链路才暴露的约定:
 * <ul>
 *   <li>路径变量与请求体的组合(如 {@code PUT /system/users/{id}/status?status=0} 用 query 而不是 body)</li>
 *   <li>各接口的必填字段与它们的先后顺序(先建类型再建字典项、先建套餐再切套餐)</li>
 *   <li>删除的前置条件(有字典项的类型删不掉、有下级的部门删不掉)</li>
 * </ul>
 *
 * <p>用平台超管是为了绕开权限码的干扰(4.10:超管的权限集合短路为全部启用菜单),
 * 这条链路本身在 {@code AdminPermissionHttpTest} 里已经验证过。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SysApiCrudHttpIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final String PLATFORM_TENANT_CODE = "platform";
    private static final long SEED_ADMIN_ID = 1L;
    /** 种子数据里的全量套餐。 */
    private static final long FULL_PACKAGE_ID = 1L;
    private static final String ADMIN_PASSWORD = "CrudAdmin@123456";
    /** 种子菜单:1=系统管理(目录)、2=用户管理(页面)、11=用户列表(按钮,父级是 2)。 */
    private static final String MENU_IDS_WITH_PARENT_CHAIN = "[1,2,11]";

    private static final java.util.concurrent.atomic.AtomicInteger PHONE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private StringRedisTemplate redis;

    private String adminUsername;
    private String token;

    @BeforeEach
    void setUpSuperAdmin() throws Exception {
        redis.delete(redis.keys("*127.0.0.1*"));
        adminUsername = "crudadmin" + suffix();
        asSuperUser(() -> {
            userService.create(new UserSaveRequest(adminUsername, ADMIN_PASSWORD, "CRUD 用例超管",
                    null, null, List.of(), 1));
            SysUser superUser = userRepository.findByTenantIdAndUsername(PLATFORM_TENANT_ID, adminUsername)
                    .orElseThrow();
            superUser.setIsSuper(1);
            superUser.setMustChangePassword(0);
            userRepository.save(superUser);
            return null;
        });
        token = login();
    }

    // ================================================================ 用户

    @Test
    @DisplayName("用户:新建 → 改名 → 停用 → 重置密码 → 解锁 → 删除")
    void userCrudCycle() throws Exception {
        String username = "cruduser" + suffix();
        Response created = post("/system/users", userBody(username, "原昵称", 1, null));
        assertThat(created.code()).as("新建用户:%s", created.body()).isEqualTo("0");
        Long userId = created.number("userId");
        assertThat(userId).isNotNull();

        // 按用户名筛选来断言"进了列表",不要用 pageNo=1&pageSize=50 赌它落在第一页 ——
        // 平台侧的用户列表是跨租户的,全量跑一轮后总数会超过 50(权限矩阵用例每个用例建 3 个账号),
        // 那时新用户翻到第二页,断言就会以一个与业务无关的原因失败
        assertThat(get("/system/users?username=" + username + "&pageSize=50", token).body()).contains(username);

        assertThat(put("/system/users/" + userId, userBody(username, "新昵称", 1, null)).code()).isEqualTo("0");
        assertThat(get("/system/users/" + userId, token).body())
                .as("改名要真的落库")
                .contains("新昵称");

        assertThat(put("/system/users/" + userId + "/status?status=0", null).code())
                .as("状态走 query 参数,不是请求体").isEqualTo("0");
        assertThat(post("/system/users/" + userId + "/password/reset", null).code())
                .as("重置密码要返回一次性明文").isEqualTo("0");
        assertThat(post("/system/users/" + userId + "/unlock", null).code()).isEqualTo("0");

        assertThat(delete("/system/users/" + userId).code()).isEqualTo("0");
        assertThat(get("/system/users?username=" + username + "&pageSize=50", token).body()).doesNotContain(username);
    }

    @Test
    @DisplayName("用户:手机号在租户内唯一,重复时必须是数据冲突而不是系统异常")
    void userDuplicatePhoneIsAConflictNotASystemError() throws Exception {
        String phone = "139" + String.format("%08d", PHONE_SEQ.incrementAndGet());
        assertThat(post("/system/users", userBody("crudph1" + suffix(), "一号", 1, null, phone)).code())
                .isEqualTo("0");

        Response duplicated = post("/system/users", userBody("crudph2" + suffix(), "二号", 1, null, phone));
        assertThat(duplicated.code())
                .as("库上有 uk_tenant_phone,这里是用户填错了输入;"
                        + "回系统异常会让管理员反复重试一个永远不会成功的请求,还会在日志里留一条 ERROR 堆栈:%s",
                        duplicated.body())
                .isEqualTo(ErrorCode.DATA_CONFLICT.code() + "");
    }

    @Test
    @DisplayName("用户:新建时用户名重复、缺少必填字段都要被拒(校验在控制器之前)")
    void userCreateValidates() throws Exception {
        String username = "cruddup" + suffix();
        assertThat(post("/system/users", userBody(username, "第一个", 1, null)).code()).isEqualTo("0");

        assertThat(post("/system/users", userBody(username, "第二个", 1, null)).code())
                .as("同租户内用户名唯一").isEqualTo(ErrorCode.DATA_CONFLICT.code() + "");
        assertThat(post("/system/users", "{\"nickname\":\"没有用户名\"}").code())
                .as("参数校验失败按约定是 200 + 40003").isEqualTo(ErrorCode.PARAM_INVALID.code() + "");
    }

    // ================================================================ 角色

    @Test
    @DisplayName("角色:新建 → 改名 → 停用 → 授权菜单 → 删除")
    void roleCrudCycle() throws Exception {
        String roleKey = "crudrole" + suffix();
        // 角色名也带上后缀:列表只支持按 roleName 筛选(没有 roleKey 参数),用固定名字就没法把查询
        // 收敛到刚建的这一条,只能赌它落在第一页 —— 数据一多就会以一个与业务无关的原因失败
        String roleName = "CRUD 角色" + suffix();
        Response created = post("/system/roles", roleBody(roleKey, roleName, 1));
        assertThat(created.code()).as("新建角色:%s", created.body()).isEqualTo("0");
        Long roleId = created.dataAsNumber();
        assertThat(roleId).isNotNull();

        assertThat(get("/system/roles?roleName=" + enc(roleName) + "&pageSize=50", token).body()).contains(roleKey);

        assertThat(put("/system/roles/" + roleId, roleBody(roleKey, roleName + "改", 1)).code()).isEqualTo("0");
        assertThat(put("/system/roles/" + roleId + "/status?status=0", null).code()).isEqualTo("0");

        // 授权要带上完整父链:只有子菜单没有父级会被拒(见 5.2 的父链校验)
        assertThat(put("/system/roles/" + roleId + "/menus", "{\"menuIds\":" + MENU_IDS_WITH_PARENT_CHAIN + "}").code())
                .isEqualTo("0");

        // 授权页要用这两个查询:可勾选的菜单集合返回菜单对象,已授权的返回**菜单 id 列表**
        assertThat(get("/system/roles/" + roleId + "/menus/grantable", token).status()).isEqualTo(200);
        Response granted = get("/system/roles/" + roleId + "/menus", token);
        assertThat(granted.body())
                .as("刚授权的菜单要能读回来,否则授权页每次打开都是空的(它回的是 id 列表,不是菜单对象)")
                .contains("\"data\":[1,2,11]");

        assertThat(delete("/system/roles/" + roleId).code()).isEqualTo("0");
    }

    @Test
    @DisplayName("角色:标识重复被拒;删除默认管理员角色被拒")
    void roleValidatesAndProtectsDefaultRole() throws Exception {
        String roleKey = "cruddup" + suffix();
        assertThat(post("/system/roles", roleBody(roleKey, "第一个", 1)).code()).isEqualTo("0");
        assertThat(post("/system/roles", roleBody(roleKey, "第二个", 1)).code())
                .as("角色标识在租户内唯一").isEqualTo(ErrorCode.DATA_CONFLICT.code() + "");

        // 平台租户的默认角色(id=1)是种子数据里那个,删掉它该租户就没有管理入口了
        Response deleteDefault = delete("/system/roles/" + 1L);
        assertThat(deleteDefault.code()).as("默认角色不可删:%s", deleteDefault.body()).isNotEqualTo("0");
    }

    // ================================================================ 套餐

    @Test
    @DisplayName("套餐:新建 → 改名 → 保存菜单 → 重新同步 → 禁用")
    void packageCrudCycle() throws Exception {
        String packageName = "CRUD 套餐" + suffix();
        Response created = post("/system/packages", "{\"packageName\":\"" + packageName + "\",\"remark\":\"用例\"}");
        assertThat(created.code()).as("新建套餐:%s", created.body()).isEqualTo("0");
        Long packageId = created.dataAsNumber();
        assertThat(packageId).isNotNull();

        assertThat(get("/system/packages?packageName=" + enc(packageName) + "&pageSize=50", token).body())
                .as("新建的套餐要能在列表里查到").contains(packageName);

        assertThat(put("/system/packages/" + packageId,
                "{\"packageName\":\"" + packageName + "改\",\"remark\":\"用例\"}").code()).isEqualTo("0");

        // 保存菜单时不许夹带平台专用菜单,也不许父链不完整
        assertThat(put("/system/packages/" + packageId + "/menus", "{\"menuIds\":" + MENU_IDS_WITH_PARENT_CHAIN + "}").code())
                .as("套餐菜单保存").isEqualTo("0");
        assertThat(put("/system/packages/" + packageId + "/menus", "{\"menuIds\":[11]}").code())
                .as("只给子菜单、不给父级必须被拒(否则菜单在租户侧挂不上)")
                .isNotEqualTo("0");

        // 套餐配置页要的这两个查询(已选菜单同样回 id 列表)
        assertThat(get("/system/packages/" + packageId + "/menus/grantable", token).status()).isEqualTo(200);
        assertThat(get("/system/packages/" + packageId + "/menus", token).body()).contains("\"data\":[1,2,11]");

        assertThat(post("/system/packages/" + packageId + "/resync", null).code())
                .as("重新同步").isEqualTo("0");
        assertThat(put("/system/packages/" + packageId + "/disable", null).code()).isEqualTo("0");
    }

    // ================================================================ 字典

    @Test
    @DisplayName("字典:先建类型再建字典项,删除要按相反的顺序")
    void dictCrudCycle() throws Exception {
        String dictType = "crud_dict_" + suffix();
        Response type = post("/system/dicts/types",
                "{\"dictType\":\"" + dictType + "\",\"dictName\":\"CRUD 字典\"}");
        assertThat(type.code()).as("新建字典类型:%s", type.body()).isEqualTo("0");
        Long typeId = type.dataAsNumber();

        assertThat(put("/system/dicts/types/" + typeId,
                "{\"dictType\":\"" + dictType + "\",\"dictName\":\"CRUD 字典改名\"}").code()).isEqualTo("0");
        assertThat(get("/system/dicts/types?dictType=" + dictType + "&pageSize=50", token).body())
                .as("新建的字典类型要能在列表里查到").contains(dictType);

        Response data = post("/system/dicts/data",
                "{\"dictType\":\"" + dictType + "\",\"dictLabel\":\"选项一\",\"dictValue\":\"1\",\"sortOrder\":1}");
        assertThat(data.code()).as("新建字典项:%s", data.body()).isEqualTo("0");
        Long dataId = data.dataAsNumber();

        assertThat(put("/system/dicts/data/" + dataId,
                "{\"dictType\":\"" + dictType + "\",\"dictLabel\":\"选项一改\",\"dictValue\":\"1\",\"sortOrder\":2}").code())
                .isEqualTo("0");
        // 管理端的字典项列表挂在类型下(/types/{dictType}/data),不是平铺的 /data?dictType=
        assertThat(get("/system/dicts/types/" + dictType + "/data", token).body())
                .as("字典项要能按类型查回来").contains("选项一改");
        assertThat(get("/system/dicts/values/" + dictType, token).body())
                .as("业务端的只读下拉项也走同一个类型").contains("选项一改");

        // 删除类型会**级联删掉它的字典项**并留一条带数量日志(设计如此,前端确认框里已写明)。
        // 所以这里断言的是"数据真的跟着没了",而不是"被拦住"
        assertThat(delete("/system/dicts/types/" + typeId).code()).isEqualTo("0");
        assertThat(get("/system/dicts/types/" + dictType + "/data", token).body())
                .as("类型删掉后它的字典项不该留下,否则就是永远查不到的孤儿数据")
                .contains("\"data\":[]");
    }

    // ================================================================ 部门与菜单

    @Test
    @DisplayName("部门:新建 → 改名 → 删除(有下级时删不掉)")
    void deptCrudCycle() throws Exception {
        String deptName = "CRUD 部门" + suffix();
        Response created = post("/system/depts",
                "{\"parentId\":0,\"deptName\":\"" + deptName + "\",\"sortOrder\":1}");
        assertThat(created.code()).as("新建部门:%s", created.body()).isEqualTo("0");
        Long deptId = created.dataAsNumber();

        assertThat(put("/system/depts/" + deptId,
                "{\"parentId\":0,\"deptName\":\"" + deptName + "改\",\"sortOrder\":2}").code()).isEqualTo("0");
        assertThat(get("/system/depts/tree", token).body()).contains(deptName + "改");

        // 挂一个子部门上来:此时父部门不能删
        Response child = post("/system/depts",
                "{\"parentId\":" + deptId + ",\"deptName\":\"" + deptName + "子\",\"sortOrder\":1}");
        assertThat(child.code()).isEqualTo("0");
        assertThat(delete("/system/depts/" + deptId).code())
                .as("有下级部门时不能删(静默裁剪会让下级部门变孤儿)")
                .isNotEqualTo("0");

        assertThat(delete("/system/depts/" + child.dataAsNumber()).code()).isEqualTo("0");
        assertThat(delete("/system/depts/" + deptId).code()).isEqualTo("0");
    }

    @Test
    @DisplayName("菜单:新建 → 改名 → 删除")
    void menuCrudCycle() throws Exception {
        Response created = post("/system/menus", menuBody(1L, "CRUD 菜单" + suffix(), 2, null));
        assertThat(created.code()).as("新建菜单:%s", created.body()).isEqualTo("0");
        Long menuId = created.dataAsNumber();

        assertThat(put("/system/menus/" + menuId, menuBody(1L, "CRUD 菜单改" + suffix(), 2, null)).code())
                .isEqualTo("0");
        assertThat(get("/system/menus/tree", token).body()).contains("CRUD 菜单改");

        assertThat(delete("/system/menus/" + menuId).code()).isEqualTo("0");
    }

    // ================================================================ 租户

    @Test
    @DisplayName("租户:新建(自动建管理员与根部门)→ 换套餐 → 停用")
    void tenantCrudCycle() throws Exception {
        String tenantCode = "crudtenant" + suffix();
        String tenantAdmin = "crudtadmin" + suffix();
        // expireTime 是必填的 @NotNull(租户必须有过期时间,否则等于永久有效);
        // 日期用项目配置的 JSON 格式(yyyy-MM-dd HH:mm:ss),不是 ISO 的 T 分隔
        Response created = post("/system/tenants", "{\"tenantCode\":\"" + tenantCode + "\",\"tenantName\":\"CRUD 租户\","
                + "\"packageId\":" + FULL_PACKAGE_ID + ",\"expireTime\":\"2030-01-01T00:00:00\","
                + "\"adminUsername\":\"" + tenantAdmin + "\",\"adminNickname\":\"租户管理员\"}");
        assertThat(created.code()).as("新建租户:%s", created.body()).isEqualTo("0");
        Long tenantId = created.number("tenantId");
        assertThat(created.text("initialPassword")).as("初始密码只返回一次").isNotBlank();
        assertThat(created.number("defaultRoleId")).as("建租户要一并建默认角色").isNotNull();
        assertThat(created.number("rootDeptId")).as("建租户要一并建根部门").isNotNull();

        // 换套餐:目标套餐得先存在。这里另建一个,避免依赖别的用例造的数据
        Response target = post("/system/packages", "{\"packageName\":\"CRUD 目标套餐" + suffix() + "\",\"remark\":null}");
        assertThat(target.code()).isEqualTo("0");
        assertThat(put("/system/tenants/" + tenantId + "/package",
                "{\"packageId\":" + target.dataAsNumber() + "}").code())
                .as("换套餐会按差异同步租户的角色,这是它最容易出错的地方")
                .isEqualTo("0");

        assertThat(put("/system/tenants/" + tenantId + "/status?status=0", null).code()).isEqualTo("0");
        assertThat(get("/system/tenants?tenantCode=" + tenantCode + "&pageSize=50", token).body())
                .as("新建的租户要能在列表里查到").contains(tenantCode);
    }

    // ================================================================ 列表筛选与不存在资源

    /**
     * 用户列表的筛选参数。
     *
     * <p>现有用例只断言"列表能返回",**查询谓词是不是真的生效从没验过** —— 谓词写漏的表现是
     * 不管传什么条件都返回全量,页面上看不出来,只觉得"用户好多"。这里用"命中 / 不命中"两侧都断言。
     */
    @Test
    @DisplayName("用户列表:按用户名与状态筛选要真的生效")
    void userListFiltering() throws Exception {
        String username = "crudfilter" + suffix();
        Response created = post("/system/users", userBody(username, "筛选用例", 1, null));
        assertThat(created.code()).as("新建用户:%s", created.body()).isEqualTo("0");
        Long userId = created.number("userId");
        assertThat(userId).as("新建用户要返回主键").isNotNull();

        try {
            Response byName = get("/system/users?username=" + username + "&pageSize=50", token);
            assertThat(byName.code()).as("用户列表查询失败:%s", byName.body()).isEqualTo("0");
            assertThat(byName.body()).as("按用户名筛选要命中这个用户").contains(username);
            assertThat(get("/system/users?username=" + username + "不存在&pageSize=50", token).body())
                    .as("按不存在的用户名筛选不该命中").doesNotContain(username);

            // 停用之后再筛:启用(1)里不该有它,停用(0)里该有它
            assertThat(put("/system/users/" + userId + "/status?status=0", null).code()).isEqualTo("0");
            assertThat(get("/system/users?username=" + username + "&status=1&pageSize=50", token).body())
                    .as("按启用状态筛选不该包含已停用的用户").doesNotContain(username);
            assertThat(get("/system/users?username=" + username + "&status=0&pageSize=50", token).body())
                    .as("按停用状态筛选应当包含它").contains(username);
        } finally {
            delete("/system/users/" + userId);
        }
    }

    /**
     * 不存在的资源要返回统一的 40400,而不是 500 或空响应。
     *
     * <p>7.3 约定:"存在但无权"与"不存在"返回同一个码。这里顺便钉住这一点 ——
     * 如果哪天改成分开返回,等于向调用方泄露了"这个 id 存在"这一信息。
     */
    @Test
    @DisplayName("不存在的主键:查询/更新/删除都要返回资源不存在(40400)")
    void missingResourcesReturnNotFound() throws Exception {
        String missing = String.valueOf(ErrorCode.NOT_FOUND.code());
        String absent = "999999999";

        assertThat(get("/system/users/" + absent, token).code())
                .as("查不存在的用户").isEqualTo(missing);
        assertThat(put("/system/users/" + absent, userBody("absent" + suffix(), "不存在", 1, null)).code())
                .as("改不存在的用户").isEqualTo(missing);
        assertThat(delete("/system/users/" + absent).code())
                .as("删不存在的用户").isEqualTo(missing);
        assertThat(post("/system/users/" + absent + "/password/reset", null).code())
                .as("给不存在的用户重置密码").isEqualTo(missing);
        assertThat(post("/system/users/" + absent + "/unlock", null).code())
                .as("给不存在的用户解锁").isEqualTo(missing);
    }

    @Test
    @DisplayName("部门与菜单树:按状态筛选要真的生效")
    void deptAndMenuTreeFiltering() throws Exception {
        String deptName = "CRUD 停用部门" + suffix();
        Response dept = post("/system/depts",
                "{\"parentId\":0,\"deptName\":\"" + deptName + "\",\"sortOrder\":1,\"status\":0}");
        assertThat(dept.code()).as("新建停用部门:%s", dept.body()).isEqualTo("0");
        Long deptId = dept.dataAsNumber();

        String menuName = "CRUD 停用菜单" + suffix();
        Response menu = post("/system/menus", menuBody(1L, menuName, 2, null));
        assertThat(menu.code()).as("新建菜单:%s", menu.body()).isEqualTo("0");
        Long menuId = menu.dataAsNumber();
        assertThat(put("/system/menus/" + menuId, menuBody(1L, menuName, 2, null)).code()).isEqualTo("0");

        try {
            // 部门树:status=1(启用)里不该出现停用部门,全量树里该出现
            assertThat(get("/system/depts/tree?status=1", token).body())
                    .as("按启用筛选不该包含停用部门").doesNotContain(deptName);
            assertThat(get("/system/depts/tree", token).body())
                    .as("不带状态筛选时应当包含停用部门").contains(deptName);

            // 菜单树:按 menuType=2(页面)筛选要能命中新菜单
            assertThat(get("/system/menus/tree?menuType=2", token).body())
                    .as("按页面类型筛选应当包含新建的页面菜单").contains(menuName);
            assertThat(get("/system/menus/tree?menuType=9", token).body())
                    .as("按不存在的菜单类型筛选不该包含它").doesNotContain(menuName);
        } finally {
            delete("/system/menus/" + menuId);
            delete("/system/depts/" + deptId);
        }
    }

    /**
     * 列表筛选参数的第二批。
     *
     * <p>上面那条只覆盖了用户名与状态,这几个接口此前都只有"列表能返回"的断言。
     * 筛选谓词写漏是**不会报错**的一类缺陷:不管传什么条件都返回全量,页面上看不出来,
     * 只觉得"数据好多"。所以每条都用"命中 / 不命中"两侧断言 —— 单侧断言对谓词写漏是绿的。
     *
     * <p>名称/编码类字段在实现里是 LIKE(contains),所以"不命中"那侧不能拿原名加个后缀当关键字:
     * 那样反而会因为包含关系命中。这里一律用与原名无包含关系的独立文案。
     */
    @Test
    @DisplayName("用户列表:按部门筛选要真的生效(deptId 写漏会让部门页显示全公司的人)")
    void userListFilteringByDept() throws Exception {
        Long deptA = post("/system/depts", deptBody("CRUD 筛选部门A" + suffix(), 1)).dataAsNumber();
        Long deptB = post("/system/depts", deptBody("CRUD 筛选部门B" + suffix(), 2)).dataAsNumber();
        assertThat(deptA).as("建 A 部门").isNotNull();
        assertThat(deptB).as("建 B 部门").isNotNull();

        String userA = "cruddepta" + suffix();
        String userB = "cruddeptb" + suffix();
        Response a = post("/system/users", userBody(userA, "A 部门的人", 1, deptA));
        Response b = post("/system/users", userBody(userB, "B 部门的人", 1, deptB));
        assertThat(a.code()).as("建 A 部门用户:%s", a.body()).isEqualTo("0");
        assertThat(b.code()).as("建 B 部门用户:%s", b.body()).isEqualTo("0");

        try {
            Response inA = get("/system/users?deptId=" + deptA + "&pageSize=100", token);
            assertThat(inA.code()).as("按部门查用户失败:%s", inA.body()).isEqualTo("0");
            assertThat(inA.body()).as("A 部门要能查到自己部门的人").contains(userA);
            assertThat(inA.body()).as("A 部门不该查出 B 部门的人").doesNotContain(userB);

            assertThat(get("/system/users?deptId=" + deptB + "&pageSize=100", token).body())
                    .as("B 部门要能查到自己部门的人").contains(userB);
        } finally {
            delete("/system/users/" + a.number("userId"));
            delete("/system/users/" + b.number("userId"));
            delete("/system/depts/" + deptA);
            delete("/system/depts/" + deptB);
        }
    }

    @Test
    @DisplayName("字典类型列表:按类型编码与名称筛选要真的生效")
    void dictTypeListFiltering() throws Exception {
        String dictType = "crud_dt_" + suffix();
        String dictName = "CRUD 字典名" + suffix();
        Long typeId = post("/system/dicts/types",
                "{\"dictType\":\"" + dictType + "\",\"dictName\":\"" + dictName + "\"}").dataAsNumber();
        assertThat(typeId).as("建字典类型").isNotNull();

        try {
            assertThat(get("/system/dicts/types?dictType=" + dictType + "&pageSize=50", token).body())
                    .as("按类型编码筛选要命中").contains(dictType);
            assertThat(get("/system/dicts/types?dictType=" + enc("nomatch_" + suffix()) + "&pageSize=50", token).body())
                    .as("按不存在的编码筛选不该命中").doesNotContain(dictType);

            assertThat(get("/system/dicts/types?dictName=" + enc(dictName) + "&pageSize=50", token).body())
                    .as("按名称筛选要命中").contains(dictType);
            assertThat(get("/system/dicts/types?dictName=" + enc("绝无此字典" + suffix()) + "&pageSize=50", token).body())
                    .as("按不存在的名称筛选不该命中").doesNotContain(dictType);
        } finally {
            delete("/system/dicts/types/" + typeId);
        }
    }

    @Test
    @DisplayName("套餐列表:按名称与状态筛选要真的生效")
    void packageListFiltering() throws Exception {
        String packageName = "CRUD 筛选套餐" + suffix();
        Long packageId = post("/system/packages",
                "{\"packageName\":\"" + packageName + "\",\"remark\":null}").dataAsNumber();
        assertThat(packageId).as("建套餐").isNotNull();

        assertThat(get("/system/packages?packageName=" + enc(packageName) + "&pageSize=50", token).body())
                .as("按名称筛选要命中").contains(packageName);
        assertThat(get("/system/packages?packageName=" + enc("绝无此套餐" + suffix()) + "&pageSize=50", token).body())
                .as("按不存在的名称筛选不该命中").doesNotContain(packageName);
        assertThat(get("/system/packages?packageName=" + enc(packageName) + "&status=1&pageSize=50", token).body())
                .as("新建的套餐默认启用").contains(packageName);

        assertThat(put("/system/packages/" + packageId + "/disable", null).code()).isEqualTo("0");
        assertThat(get("/system/packages?packageName=" + enc(packageName) + "&status=1&pageSize=50", token).body())
                .as("禁用后不该再出现在启用列表里").doesNotContain(packageName);
        assertThat(get("/system/packages?packageName=" + enc(packageName) + "&status=0&pageSize=50", token).body())
                .as("禁用后应当出现在停用列表里").contains(packageName);
    }

    @Test
    @DisplayName("角色列表:按名称与状态筛选要真的生效")
    void roleListFiltering() throws Exception {
        String roleName = "CRUD 筛选角色" + suffix();
        Long roleId = post("/system/roles", roleBody("crudfr" + suffix(), roleName, 1)).dataAsNumber();
        assertThat(roleId).as("建角色").isNotNull();

        try {
            assertThat(get("/system/roles?roleName=" + enc(roleName) + "&pageSize=50", token).body())
                    .as("按名称筛选要命中").contains(roleName);
            assertThat(get("/system/roles?roleName=" + enc("绝无此角色" + suffix()) + "&pageSize=50", token).body())
                    .as("按不存在的名称筛选不该命中").doesNotContain(roleName);
            assertThat(get("/system/roles?roleName=" + enc(roleName) + "&status=1&pageSize=50", token).body())
                    .as("新建的角色默认启用").contains(roleName);

            assertThat(put("/system/roles/" + roleId + "/status?status=0", null).code()).isEqualTo("0");
            assertThat(get("/system/roles?roleName=" + enc(roleName) + "&status=1&pageSize=50", token).body())
                    .as("停用后不该再出现在启用列表里").doesNotContain(roleName);
            assertThat(get("/system/roles?roleName=" + enc(roleName) + "&status=0&pageSize=50", token).body())
                    .as("停用后应当出现在停用列表里").contains(roleName);
        } finally {
            delete("/system/roles/" + roleId);
        }
    }

    @Test
    @DisplayName("租户列表:按编码与状态筛选要真的生效")
    void tenantListFiltering() throws Exception {
        String tenantCode = "crudft" + suffix();
        Response created = post("/system/tenants", "{\"tenantCode\":\"" + tenantCode
                + "\",\"tenantName\":\"CRUD 筛选租户\",\"packageId\":" + FULL_PACKAGE_ID
                + ",\"expireTime\":\"2030-01-01T00:00:00\",\"adminUsername\":\"crudftadmin" + suffix()
                + "\",\"adminNickname\":\"筛选租户管理员\"}");
        assertThat(created.code()).as("建租户:%s", created.body()).isEqualTo("0");
        Long tenantId = created.number("tenantId");
        assertThat(tenantId).as("建租户要返回主键").isNotNull();

        assertThat(get("/system/tenants?tenantCode=" + tenantCode + "&pageSize=50", token).body())
                .as("按编码筛选要命中").contains(tenantCode);
        assertThat(get("/system/tenants?tenantCode=" + enc("nomatch" + suffix()) + "&pageSize=50", token).body())
                .as("按不存在的编码筛选不该命中").doesNotContain(tenantCode);
        assertThat(get("/system/tenants?tenantCode=" + tenantCode + "&status=1&pageSize=50", token).body())
                .as("新建的租户默认启用").contains(tenantCode);

        assertThat(put("/system/tenants/" + tenantId + "/status?status=0", null).code()).isEqualTo("0");
        assertThat(get("/system/tenants?tenantCode=" + tenantCode + "&status=1&pageSize=50", token).body())
                .as("停用后不该再出现在启用列表里").doesNotContain(tenantCode);
        assertThat(get("/system/tenants?tenantCode=" + tenantCode + "&status=0&pageSize=50", token).body())
                .as("停用后应当出现在停用列表里").contains(tenantCode);
    }

    @Test
    @DisplayName("字典取值:未知字典类型返回空列表而不是报错(否则前端下拉会因配置缺失整页炸)")
    void dictValuesOfUnknownTypeIsEmpty() throws Exception {
        Response unknown = get("/system/dicts/values/绝对不存在的字典类型" + suffix(), token);
        assertThat(unknown.code()).as("响应=%s", unknown.body()).isEqualTo("0");
        assertThat(unknown.body()).as("未知类型应当返回空列表").contains("\"data\":[]");
    }

    /**
     * 全局异常处理的两个兜底分支(架构文档 7.2)。
     *
     * <p>这两个分支此前没有任何用例:请求体不是合法 JSON 时走 {@code HttpMessageNotReadable} 分支;
     * 而路径变量类型对不上时没有专门的 handler,会落到 {@code Exception} 兜底。
     *
     * <p>它们都是"调用方写错"的场景,**必须返回能看懂的业务码而不是把堆栈抛给前端**。
     * 第二条断言的是当前实现:类型不匹配被兜底成 50001。严格说它更适合 40003(是调用方的错),
     * 但改成 40003 需要新增 handler —— 这条用例的作用是把现状钉住,让"改"变成一个显式决定,
     * 而不是某次重构顺手改掉却没人知道。
     */
    @Test
    @DisplayName("异常处理:请求体不是合法 JSON、路径变量类型不对,都要返回业务码")
    void malformedRequestsAreTranslatedToBusinessCodes() throws Exception {
        Response unreadable = post("/system/users", "{不是 JSON");
        assertThat(unreadable.code())
                .as("请求体解析失败要翻译成 40003,响应=%s", unreadable.body())
                .isEqualTo(String.valueOf(ErrorCode.PARAM_INVALID.code()));
        assertThat(unreadable.body()).as("要给前端一句能直接显示的话").contains("请求体格式不正确");

        Response badPathVariable = get("/system/users/not-a-number", token);
        assertThat(badPathVariable.status()).as("不能把异常抛成 500 页面").isEqualTo(200);
        assertThat(badPathVariable.code())
                .as("路径变量类型不匹配应当落到兜底分支,响应=%s", badPathVariable.body())
                .isEqualTo(String.valueOf(ErrorCode.SYSTEM_ERROR.code()));
    }

    // ================================================================ 请求构造

    private String userBody(String username, String nickname, Integer status, Long deptId) {
        // 手机号在租户内唯一(uk_tenant_phone):用例必须一人一号,所以这里自动生成一个没用过的
        return userBody(username, nickname, status, deptId,
                "138" + String.format("%08d", PHONE_SEQ.incrementAndGet()));
    }

    private String userBody(String username, String nickname, Integer status, Long deptId, String phone) {
        return "{\"username\":\"" + username + "\",\"password\":\"Crud@123456\",\"nickname\":\"" + nickname
                + "\",\"phone\":\"" + phone + "\",\"deptId\":" + (deptId == null ? "null" : deptId)
                + ",\"roleIds\":[],\"status\":" + status + "}";
    }

    private String roleBody(String roleKey, String roleName, Integer dataScope) {
        return "{\"roleKey\":\"" + roleKey + "\",\"roleName\":\"" + roleName + "\",\"dataScope\":" + dataScope
                + ",\"deptIds\":null,\"status\":1}";
    }

    private String menuBody(Long parentId, String menuName, Integer menuType, String permCode) {
        return "{\"parentId\":" + parentId + ",\"menuName\":\"" + menuName + "\",\"menuType\":" + menuType
                + ",\"routePath\":null,\"permCode\":" + (permCode == null ? "null" : "\"" + permCode + "\"")
                + ",\"icon\":null,\"sortOrder\":1,\"status\":1}";
    }

    private String deptBody(String deptName, Integer status) {
        return "{\"parentId\":0,\"deptName\":\"" + deptName + "\",\"sortOrder\":1,\"status\":" + status + "}";
    }

    /** 查询参数的值要编码:中文与空格都不能直接进 URI。 */
    private String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String login() throws Exception {
        // 此时 token 还是 null,所以这个请求不带 Authorization(登录本来也不需要)
        Response login = post("/auth/login", "{\"tenantCode\":\"" + PLATFORM_TENANT_CODE + "\",\"username\":\""
                + adminUsername + "\",\"password\":\"" + ADMIN_PASSWORD + "\",\"deviceId\":\"crud-it\"}");
        assertThat(login.code()).as("登录必须成功:响应=%s", login.body()).isEqualTo("0");
        String accessToken = login.text("token");
        assertThat(accessToken).isNotBlank();
        return accessToken;
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, SEED_ADMIN_ID, "127.0.0.1", "crud-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private Response get(String path, String accessToken) throws Exception {
        return send(HttpRequest.newBuilder().GET(), path, accessToken);
    }

    private Response post(String path, String jsonBody) throws Exception {
        return send(HttpRequest.newBuilder().POST(body(jsonBody)), path, token);
    }

    private Response put(String path, String jsonBody) throws Exception {
        return send(HttpRequest.newBuilder().PUT(body(jsonBody)), path, token);
    }

    private Response delete(String path) throws Exception {
        return send(HttpRequest.newBuilder().DELETE(), path, token);
    }

    /** 没有请求体的写接口:Spring 对 PUT/POST 也接受空的 noBody。 */
    private HttpRequest.BodyPublisher body(String jsonBody) {
        return jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
    }

    private Response send(HttpRequest.Builder builder, String path, String accessToken) throws Exception {
        builder.uri(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {

        String code() {
            Matcher matcher = Pattern.compile("\"code\":(\\d+)").matcher(body);
            return matcher.find() ? matcher.group(1) : "-1";
        }

        String text(String field) {
            Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        }

        Long number(String field) {
            Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
            return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
        }

        /**
         * 返回裸 {@code Long} 的新建接口把主键放在 {@code data} 上 —— 形如
         * {@code {"code":0,"message":"ok","data":123}}。这时没有具名字段可读,
         * 用 {@link #number(String)} 会得到 null,请求就会打成 {@code /system/depts/null}
         * 并报参数类型转换失败(排查起来像"分页参数错了",其实根因在建返回值的解析上)。
         */
        Long dataAsNumber() {
            Matcher matcher = Pattern.compile("\"data\":(\\d+)").matcher(body);
            return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
        }
    }
}
