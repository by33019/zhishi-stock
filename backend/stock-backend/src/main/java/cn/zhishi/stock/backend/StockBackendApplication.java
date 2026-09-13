package cn.zhishi.stock.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(
        scanBasePackages = "cn.zhishi.stock",
        exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan(basePackages = "cn.zhishi.stock.system.auth", annotationClass = Mapper.class)
public class StockBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockBackendApplication.class, args);
    }
}
