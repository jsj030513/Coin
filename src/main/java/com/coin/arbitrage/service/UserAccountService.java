package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.UserAccountEntity;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{3,39}$");
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final com.coin.arbitrage.persistence.InviteCodeRepository invites;
    private final com.coin.arbitrage.persistence.PortfolioOnboardingRepository onboarding;

    public UserAccountService(UserAccountRepository users, PasswordEncoder passwordEncoder,
                              com.coin.arbitrage.persistence.InviteCodeRepository invites,
                              com.coin.arbitrage.persistence.PortfolioOnboardingRepository onboarding) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.invites = invites;
        this.onboarding = onboarding;
    }

    @Transactional
    public UserAccountEntity register(String username, String password, String displayName, String inviteCode) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        String name = displayName == null ? "" : displayName.trim();
        if (!USERNAME.matcher(normalized).matches())
            throw new IllegalArgumentException("아이디는 영문 소문자·숫자로 시작하는 4~40자여야 합니다.");
        if (password == null || password.length() < 10 || password.length() > 100)
            throw new IllegalArgumentException("비밀번호는 10~100자로 설정해 주세요.");
        if (name.isBlank() || name.length() > 50)
            throw new IllegalArgumentException("표시 이름은 1~50자로 입력해 주세요.");
        if (name.indexOf('<') >= 0 || name.indexOf('>') >= 0 || name.indexOf('&') >= 0)
            throw new IllegalArgumentException("표시 이름에는 HTML 특수문자를 사용할 수 없습니다.");
        if (users.existsByUsername(normalized))
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
        var invite = invites.findById(inviteCode == null ? "" : inviteCode.trim())
                .filter(value -> value.getUsedAt() == null && value.getExpiresAt().isAfter(java.time.Instant.now()))
                .orElseThrow(() -> new IllegalArgumentException("유효한 관리자 초대코드가 필요합니다."));
        UserAccountEntity user = users.save(new UserAccountEntity(normalized, passwordEncoder.encode(password), name));
        onboarding.save(new com.coin.arbitrage.persistence.PortfolioOnboardingEntity(user));
        invite.use(normalized); invites.save(invite); return user;
    }

    @Transactional
    public UserAccountEntity createAdmin(String username, String password, String displayName) {
        UserAccountEntity user = users.findByUsername(username).orElseGet(() ->
                createBootstrapUser(username, password, displayName));
        user.promoteAdmin();
        return users.save(user);
    }

    private UserAccountEntity createBootstrapUser(String username,String password,String displayName) {
        String normalized=username.trim().toLowerCase(Locale.ROOT);
        return users.save(new UserAccountEntity(normalized,passwordEncoder.encode(password),displayName));
    }

    @Transactional public void approve(String username){UserAccountEntity user=require(username);user.approve();users.save(user);}
    @Transactional public void lock(String username,boolean locked){UserAccountEntity user=require(username);user.setLocked(locked);users.save(user);}

    @Transactional
    public void withdraw(String username, String password) {
        UserAccountEntity user = require(username);
        if ("ADMIN".equals(user.getRole())) throw new IllegalArgumentException("관리자 계정은 탈퇴할 수 없습니다.");
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash()))
            throw new IllegalArgumentException("비밀번호가 올바르지 않습니다.");
        user.withdraw(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
        users.save(user);
    }

    public UserAccountEntity require(String username) {
        return users.findByUsername(username).orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        UserAccountEntity user = require(username);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다.");
        }
        if (passwordEncoder.matches(newPassword == null ? "" : newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("새 비밀번호는 현재 비밀번호와 달라야 합니다.");
        }
        updatePassword(user, newPassword);
    }

    @Transactional
    public void resetPassword(String username, String newPassword) {
        updatePassword(require(username), newPassword);
    }

    private void updatePassword(UserAccountEntity user, String password) {
        validatePassword(password);
        user.changePasswordHash(passwordEncoder.encode(password));
        users.save(user);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 10 || password.length() > 100) {
            throw new IllegalArgumentException("비밀번호는 10~100자로 설정해 주세요.");
        }
    }
}
