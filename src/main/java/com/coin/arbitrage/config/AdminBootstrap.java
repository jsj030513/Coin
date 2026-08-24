package com.coin.arbitrage.config;

import com.coin.arbitrage.service.UserAccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final UserAccountService users;
    private final String username, password;
    public AdminBootstrap(UserAccountService users,
                          @Value("${admin.bootstrap-username:}") String username,
                          @Value("${admin.bootstrap-password:}") String password) {
        this.users=users; this.username=username.trim(); this.password=password;
    }
    public void run(ApplicationArguments args) {
        if (!username.isBlank() && password != null && password.length() >= 10)
            users.createAdmin(username, password, "관리자");
    }
}
