package com.minimall.sys.domain.repository;

import com.minimall.sys.domain.SysTaskRunLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务执行历史仓储(架构文档 6.2)。
 *
 * <p>写入来自**调度线程**(没有租户上下文),所以本表是平台级的(见实体注释);
 * 查询用 Querydsl 的动态条件 —— 任务名与结果都是可选筛选项。
 */
public interface SysTaskRunLogRepository extends JpaRepository<SysTaskRunLog, Long>,
        QuerydslPredicateExecutor<SysTaskRunLog> {

    /**
     * 清理保留期之前的记录。
     *
     * <p>不带条件地留着的话,这张表会变成"只增不减" —— 一分钟一次的任务一年就是几十万行,
     * 和审计日志是同一类问题(见架构文档 7.2)。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SysTaskRunLog l where l.createTime < :deadline")
    int deleteCreatedBefore(@Param("deadline") LocalDateTime deadline);

    /** 历史里出现过的任务名。对着它看哪个任务没有近期记录,就知道谁停了。 */
    @Query("select distinct l.taskName from SysTaskRunLog l order by l.taskName asc")
    List<String> findDistinctTaskNames();
}
