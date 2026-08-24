package com.coin.arbitrage.persistence;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="user_trading_preferences") public class UserTradingPreferenceEntity {
 @Id private Long userId; @OneToOne(fetch=FetchType.LAZY,optional=false) @MapsId @JoinColumn(name="user_id") private UserAccountEntity user;
 private long plannedCapitalKrw; private long minExchangeKrw; private int plannedSymbolCount; private Instant updatedAt;
 protected UserTradingPreferenceEntity(){} public UserTradingPreferenceEntity(UserAccountEntity user){this.user=user;update(0,0,5);}
 public void update(long capital,long minimum,int symbols){plannedCapitalKrw=Math.max(0,capital);minExchangeKrw=Math.max(0,minimum);plannedSymbolCount=Math.max(1,Math.min(10,symbols));updatedAt=Instant.now();}
 public long getPlannedCapitalKrw(){return plannedCapitalKrw;} public long getMinExchangeKrw(){return minExchangeKrw;} public int getPlannedSymbolCount(){return plannedSymbolCount;} public Instant getUpdatedAt(){return updatedAt;}
}
