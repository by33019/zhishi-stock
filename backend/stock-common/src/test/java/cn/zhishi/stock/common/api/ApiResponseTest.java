package cn.zhishi.stock.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

  @Test
  void successResponseContainsStableEnvelopeAndShanghaiTimestamp() {
    var timestamp = OffsetDateTime.of(2026, 9, 11, 9, 30, 0, 0, ZoneOffset.ofHours(8));

    var response = ApiResponse.success(Map.of("market", "CN"), "trace-001", timestamp);

    assertThat(response.success()).isTrue();
    assertThat(response.code()).isEqualTo("SUCCESS");
    assertThat(response.message()).isEqualTo("操作成功");
    assertThat(response.data()).containsEntry("market", "CN");
    assertThat(response.traceId()).isEqualTo("trace-001");
    assertThat(response.timestamp().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
  }
}
