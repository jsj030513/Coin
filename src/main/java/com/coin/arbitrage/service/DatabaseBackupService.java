package com.coin.arbitrage.service;
import java.nio.file.*; import java.time.*; import java.time.format.DateTimeFormatter; import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.scheduling.annotation.Scheduled; import org.springframework.stereotype.Service;
@Service public class DatabaseBackupService {
 private static final Path DIR=Path.of("data","backups"); private final JdbcTemplate jdbc;
 public DatabaseBackupService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Scheduled(cron="${backup.cron:0 10 3 * * *}",zone="Asia/Seoul") public void scheduled(){create();}
 public synchronized String create(){try{Files.createDirectories(DIR);String name="arbitrage-"+DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now(ZoneId.of("Asia/Seoul")))+".zip";String path=DIR.resolve(name).toAbsolutePath().toString().replace("'","''");jdbc.execute("BACKUP TO '"+path+"'");prune();return name;}catch(Exception e){throw new IllegalStateException("데이터베이스 백업 실패",e);}}
 public List<String> list(){try{if(!Files.exists(DIR))return List.of();try(var s=Files.list(DIR)){return s.filter(p->p.getFileName().toString().endsWith(".zip")).sorted(Comparator.reverseOrder()).map(p->p.getFileName().toString()).toList();}}catch(Exception e){return List.of();}}
 private void prune()throws Exception{List<Path> rows;try(var s=Files.list(DIR)){rows=s.filter(p->p.getFileName().toString().endsWith(".zip")).sorted(Comparator.reverseOrder()).toList();}for(int i=14;i<rows.size();i++)Files.deleteIfExists(rows.get(i));}
}
