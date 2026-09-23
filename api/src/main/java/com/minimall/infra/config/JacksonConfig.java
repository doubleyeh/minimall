package com.minimall.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** 装箱 Long 序列化成字符串:雪花 ID 超出 JS 安全整数,前端解析后会被改成另一个数。 */
@Configuration
public class JacksonConfig {

    /** 只注册装箱 Long。基本类型 long 不动,分页的 total 必须保持数字。 */
    @Bean
    public JacksonModule longToStringModule() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        return module;
    }
}
