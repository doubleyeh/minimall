package com.minimall.infra.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记"需要落操作日志"的接口方法(架构文档 7.2)。
 *
 * <p>为什么用注解而不是"切所有 Controller":操作日志的价值在于**可枚举**——
 * 哪些操作被记录了应该是能一眼看出来的。切包名会让日志范围随代码结构调整而漂移,
 * 而且查询类接口(看列表、看详情)记日志只有噪音,没有价值。
 *
 * <p>三个要素都写在这里,由 {@link OperLogAspect} 取走写进 {@code sys_oper_log}:
 * <ul>
 *   <li>{@link #module()} → {@code module} 列(如"用户管理")</li>
 *   <li>{@link #permCode()} → {@code perm_code} 列。**建议与接口上的 {@code @SaCheckPermission}
 *       保持一致**,这样"谁能做"与"做过什么"能对上,排查时不用再翻代码</li>
 *   <li>{@link #recordParams()} → 是否记录请求参数。**默认 true**;当参数里必然含大字段
 *       (如批量导入的明细)时显式关掉,避免日志表被撑爆</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /** 业务模块名,写进 sys_oper_log.module,便于按模块筛选。 */
    String module();

    /** 权限码,建议与 @SaCheckPermission 的值一致(见类注释)。 */
    String permCode() default "";

    /** 是否记录请求参数(默认记录,写入前会脱敏与截断)。 */
    boolean recordParams() default true;
}
