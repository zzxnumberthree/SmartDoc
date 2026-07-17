package com.spe.smartdocjp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;


@SpringBootApplication
@EnableAspectJAutoProxy
public class SmartDocJpApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartDocJpApplication.class, args);
    }

}
