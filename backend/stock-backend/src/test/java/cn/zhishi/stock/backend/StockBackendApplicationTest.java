package cn.zhishi.stock.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class StockBackendApplicationTest {

  @Test
  void declaresBootApplicationEntryPoint() {
    assertThat(StockBackendApplication.class)
        .hasAnnotation(SpringBootApplication.class);
  }
}
