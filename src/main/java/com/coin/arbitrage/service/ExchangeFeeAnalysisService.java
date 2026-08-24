package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.ExchangeConnectionEntity;
import com.coin.arbitrage.persistence.ExchangeConnectionRepository;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ExchangeFeeAnalysisService {
    private final UserAccountRepository users;
    private final ExchangeConnectionRepository connections;
    private final ExchangeConnectionService service;
    public ExchangeFeeAnalysisService(UserAccountRepository users,ExchangeConnectionRepository connections,
                                      ExchangeConnectionService service){this.users=users;this.connections=connections;this.service=service;}

    @Scheduled(cron="${fees.refresh-cron:0 20 5 * * *}",zone="Asia/Seoul")
    public void refreshAllUsers(){users.findAll().stream().filter(u->!"ADMIN".equals(u.getRole())).filter(u->u.getWithdrawnAt()==null)
            .forEach(u->{try{refreshUser(u.getUsername());}catch(RuntimeException ignored){}});}

    public List<ExchangeConnectionService.ConnectionView> refreshUser(String username){
        List<ExchangeConnectionService.ConnectionView> result=new ArrayList<>();
        connections.findByUserUsernameOrderByExchangeAsc(username).stream()
                .filter(c->c.getStatus()==ExchangeConnectionEntity.Status.VERIFIED)
                .forEach(c->{try{result.add(service.refreshFees(username,c.getExchange().name()));}catch(RuntimeException ignored){}});
        return result;
    }
}
