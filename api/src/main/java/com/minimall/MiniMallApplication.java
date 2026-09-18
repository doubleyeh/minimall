package com.minimall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 单体应用脚手架启动类(RBAC + 多租户)。
 *
 * <p>分层与依赖方向见架构文档第 3 节:
 * api -> service -> domain;infra 被 api/service 依赖,不反向依赖;common 谁都可以依赖,但它不依赖任何人。
 * 依赖方向由 {@code ArchitectureTest} 守门,违反即构建失败。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MiniMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniMallApplication.class, args);
    }
}
