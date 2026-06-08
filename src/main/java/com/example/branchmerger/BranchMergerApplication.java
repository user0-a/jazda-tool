package com.example.branchmerger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.branchmerger.config.GitProperties;

@SpringBootApplication
@EnableConfigurationProperties(GitProperties.class)
public class BranchMergerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BranchMergerApplication.class, args);
    }
}
