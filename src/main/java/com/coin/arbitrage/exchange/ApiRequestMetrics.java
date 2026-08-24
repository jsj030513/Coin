package com.coin.arbitrage.exchange;
import java.time.Instant; import java.util.Map; import java.util.concurrent.ConcurrentHashMap; import java.util.concurrent.atomic.AtomicLong;
public final class ApiRequestMetrics {
 private static final Instant STARTED=Instant.now(); private static final Map<String,AtomicLong> COUNTS=new ConcurrentHashMap<>(); private ApiRequestMetrics(){}
 static void record(String exchange,int status){COUNTS.computeIfAbsent(exchange.toUpperCase()+"_HTTP_"+status,k->new AtomicLong()).incrementAndGet();}
 static void recordFailure(String exchange){COUNTS.computeIfAbsent(exchange.toUpperCase()+"_FAILURE",k->new AtomicLong()).incrementAndGet();}
 public static Snapshot snapshot(){return new Snapshot(STARTED,COUNTS.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,e->e.getValue().get())));}
 public record Snapshot(Instant since,Map<String,Long> counts){}
}
