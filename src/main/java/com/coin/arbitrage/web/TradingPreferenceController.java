package com.coin.arbitrage.web;
import com.coin.arbitrage.service.UserTradingPreferenceService; import java.security.Principal; import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity; import java.util.Map;
@RestController @RequestMapping("/api/trading-preferences") public class TradingPreferenceController {
 private final UserTradingPreferenceService service; public TradingPreferenceController(UserTradingPreferenceService service){this.service=service;}
 @GetMapping public UserTradingPreferenceService.Preference get(Principal p){return service.get(p.getName());}
 @PutMapping public UserTradingPreferenceService.Preference update(Principal p,@RequestBody Request r){return service.update(p.getName(),r.plannedCapitalKrw(),r.minExchangeKrw(),r.plannedSymbolCount());}
 @PostMapping("/auto-symbols") public AutoSymbols autoSymbols(Principal p,@RequestBody AutoSymbolsRequest r){int symbols=UserTradingPreferenceService.recommendedSymbolCount(r.plannedCapitalKrw());var saved=service.updateAutoSymbols(p.getName(),r.plannedCapitalKrw(),r.minExchangeKrw());return new AutoSymbols(symbols,saved);}
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
 public record Request(long plannedCapitalKrw,long minExchangeKrw,int plannedSymbolCount){}
 public record AutoSymbolsRequest(long plannedCapitalKrw,long minExchangeKrw){}
 public record AutoSymbols(int recommendedSymbolCount,UserTradingPreferenceService.Preference preference){}
}
