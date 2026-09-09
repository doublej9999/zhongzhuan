package com.example.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * NAS → S3 → B2B Gateway → 客户 SFTP 中转系统入口。
 *
 * <p>Phase 1：仅工程骨架，无业务逻辑。后续模块：scanner / file / task / worker / storage /
 * gateway / retry / repository / audit / monitoring。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TransferApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransferApplication.class, args);
    }
}
