package com.coin.arbitrage.service;
import com.coin.arbitrage.persistence.*; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
@Service public class UserTradingPreferenceService {
 private final UserTradingPreferenceRepository rows; private final UserAccountRepository users;
 public UserTradingPreferenceService(UserTradingPreferenceRepository rows,UserAccountRepository users){this.rows=rows;this.users=users;}
 @Transactional public Preference get(String username){return view(entity(username));}
 @Transactional public Preference update(String username,long capital,long minimum,int symbols){if(capital>0&&capital<50_000)throw new IllegalArgumentException("운용 예정금액은 최소 5만원 이상 입력해 주세요.");if(minimum<0||(capital>0&&minimum>capital))throw new IllegalArgumentException("거래소 최소 현금이 운용금액보다 클 수 없습니다.");var row=entity(username);row.update(capital,minimum,symbols);return view(rows.save(row));}
 @Transactional public Preference updateAutoSymbols(String username,long capital,long minimum){return update(username,capital,minimum,recommendedSymbolCount(capital));}
 private UserTradingPreferenceEntity entity(String username){return rows.findByUserUsername(username).orElseGet(()->rows.save(new UserTradingPreferenceEntity(users.findByUsername(username).orElseThrow())));}
 private static Preference view(UserTradingPreferenceEntity r){return new Preference(r.getPlannedCapitalKrw(),r.getMinExchangeKrw(),r.getPlannedSymbolCount(),r.getUpdatedAt());}
 public static int recommendedSymbolCount(long capitalKrw){if(capitalKrw<100_000)return 1;if(capitalKrw<200_000)return 2;if(capitalKrw<350_000)return 3;if(capitalKrw<600_000)return 4;if(capitalKrw<1_000_000)return 5;if(capitalKrw<2_000_000)return 7;return 10;}
 public record Preference(long plannedCapitalKrw,long minExchangeKrw,int plannedSymbolCount,java.time.Instant updatedAt){}
}
