package com.zhihu.kanshan.debate;

import org.salt.jlangchain.config.JLangchainConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@Import(JLangchainConfig.class)
@EnableAsync
public class KanshanDebateApplication {
    public static void main(String[] args) {
        SpringApplication.run(KanshanDebateApplication.class, args);
    }
}
