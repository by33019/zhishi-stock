package cn.zhishi.stock.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class DevelopmentAccountSeederTest {

    @Test
    void isRestrictedToDevelopmentAndTestProfiles() {
        Profile profile = DevelopmentAccountSeeder.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(Set.of(profile.value())).containsExactlyInAnyOrder("dev", "test");
    }
}
