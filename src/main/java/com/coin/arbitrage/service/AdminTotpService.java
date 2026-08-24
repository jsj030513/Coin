package com.coin.arbitrage.service;
import com.coin.arbitrage.persistence.*; import java.nio.ByteBuffer; import java.security.SecureRandom; import javax.crypto.Mac; import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
@Service public class AdminTotpService {
 private static final String ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; private final AdminTotpRepository rows; private final UserAccountRepository users; private final CredentialEncryptionService crypto;
 public AdminTotpService(AdminTotpRepository rows,UserAccountRepository users,CredentialEncryptionService crypto){this.rows=rows;this.users=users;this.crypto=crypto;}
 public boolean enabled(String username){return rows.findByUserUsername(username).map(AdminTotpEntity::isEnabled).orElse(false);}
 @Transactional public Setup setup(String username){byte[] raw=new byte[20];new SecureRandom().nextBytes(raw);String secret=encode(raw);var user=users.findByUsername(username).orElseThrow();var row=rows.findByUserUsername(username).orElseGet(()->new AdminTotpEntity(user,crypto.encrypt(secret)));row.replace(crypto.encrypt(secret));rows.save(row);return new Setup(secret,"otpauth://totp/ARB%20KOREA:"+username+"?secret="+secret+"&issuer=ARB%20KOREA");}
 @Transactional public boolean verifyAndEnable(String username,String code){var row=rows.findByUserUsername(username).orElseThrow();boolean ok=verifyCode(crypto.decrypt(row.getEncryptedSecret()),code);if(ok){row.enable();rows.save(row);}return ok;}
 public boolean verify(String username,String code){return rows.findByUserUsername(username).filter(AdminTotpEntity::isEnabled).map(r->verifyCode(crypto.decrypt(r.getEncryptedSecret()),code)).orElse(false);}
 private static boolean verifyCode(String secret,String code){if(code==null||!code.matches("\\d{6}"))return false;long step=System.currentTimeMillis()/30000;for(long i=-1;i<=1;i++)if(code.equals(generate(secret,step+i)))return true;return false;}
 private static String generate(String secret,long step){try{Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(decode(secret),"HmacSHA1"));byte[] h=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());int o=h[h.length-1]&15;int n=((h[o]&127)<<24)|((h[o+1]&255)<<16)|((h[o+2]&255)<<8)|(h[o+3]&255);return "%06d".formatted(n%1000000);}catch(Exception e){throw new IllegalStateException(e);}}
 private static String encode(byte[] data){StringBuilder s=new StringBuilder();int buffer=0,bits=0;for(byte b:data){buffer=(buffer<<8)|(b&255);bits+=8;while(bits>=5){s.append(ALPHABET.charAt((buffer>>(bits-=5))&31));}}if(bits>0)s.append(ALPHABET.charAt((buffer<<(5-bits))&31));return s.toString();}
 private static byte[] decode(String s){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();int buffer=0,bits=0;for(char c:s.toCharArray()){int v=ALPHABET.indexOf(c);if(v<0)continue;buffer=(buffer<<5)|v;bits+=5;if(bits>=8){out.write((buffer>>(bits-=8))&255);}}return out.toByteArray();}
 public record Setup(String secret,String uri){}
}
