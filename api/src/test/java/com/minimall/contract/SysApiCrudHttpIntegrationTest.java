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

        assertThat(get("/system/users?pageNo=1&pageSize=50", token).body()).contains(username);

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
        assertThat(get("/system/users?pageNo=1&pageSize=50", token).body()).doesNotContain(username);
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
        Response created = post("/system/roles", roleBody(roleKey, "CRUD 角色", 1));
        assertThat(created.code()).as("新建角色:%s", created.body()).isEqualTo("0");
        Long roleId = created.dataAsNumber();
        assertThat(roleId).isNotNull();

        assertThat(get("/system/roles?pageNo=1&pageSize=50", token).body()).contains(roleKey);

        assertThat(put("/system/roles/" + roleId, roleBody(roleKey, "CRUD 角色改名", 1)).code()).isEqualTo("0");
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

        assertThat(get("/system/packages?pageNo=1&pageSize=50", token).body()).contains(packageName);

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
        assertThat(get("/system/dicts/types?pageNo=1&pageSize=50", token).body()).contains(dictType);

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
        assertThat(get("/system/tenants?pageNo=1&pageSize=50", token).body()).contains(tenantCode);
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
