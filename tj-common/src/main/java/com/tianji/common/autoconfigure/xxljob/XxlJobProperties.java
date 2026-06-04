package com.tianji.common.autoconfigure.xxljob;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "tj.xxl-job")//请你去 Nacos 以及本地的 application.yml 里面找一找，只把以 tj.xxl-job 开头的配置项给我拿过来
public class XxlJobProperties {

    private String accessToken;
    private Admin admin;
    private Executor executor;

    @Data
    public static class Admin {
        private String address;
    }

    @Data
    public static class Executor {
        private String appName;
        private String address;
        private String ip;
        private Integer port;
        private String logPath;
        private Integer logRetentionDays;

    }
}