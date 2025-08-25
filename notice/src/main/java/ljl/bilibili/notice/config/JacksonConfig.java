package ljl.bilibili.notice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Configuration
public class JacksonConfig {

    /**
     * 配置自定义的ObjectMapper作为Spring Bean，用于JSON数据的序列化和反序列化
     * 主要解决Java 8日期时间类型(如LocalDateTime)的JSON格式问题
     * @return 配置好的ObjectMapper实例
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 初始化JavaTimeModule模块，用于支持Java 8新增的日期时间API(如LocalDateTime)
        // 返回配置完成的ObjectMapper实例，交给Spring容器管理
        // 将配置好的JavaTimeModule注册到ObjectMapper中，使其生效
        // 向模块注册LocalDateTime类型的自定义序列化器
        // 创建LocalDateTime序列化器，指定使用ISO标准日期时间格式(如: 2023-10-05T14:30:00)
        JavaTimeModule module = new JavaTimeModule();
        LocalDateTimeSerializer localDateTimeSerializer = new LocalDateTimeSerializer(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        module.addSerializer(LocalDateTime.class, localDateTimeSerializer);
        mapper.registerModule(module);
        return mapper;
    }
}
