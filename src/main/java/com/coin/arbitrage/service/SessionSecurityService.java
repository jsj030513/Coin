package com.coin.arbitrage.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

@Service
public class SessionSecurityService {
    private final SessionRegistry sessions;

    public SessionSecurityService(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    public void expireAll(String username) {
        sessions.getAllPrincipals().stream()
                .filter(principal -> username.equals(nameOf(principal)))
                .flatMap(principal -> sessions.getAllSessions(principal, false).stream())
                .forEach(session -> session.expireNow());
    }

    private static String nameOf(Object principal) {
        return principal instanceof UserDetails details ? details.getUsername() : String.valueOf(principal);
    }
}
