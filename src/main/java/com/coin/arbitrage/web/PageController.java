package com.coin.arbitrage.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PageController {
    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE) @ResponseBody
    public ResponseEntity<Resource> login() { return page("login.html"); }
    @GetMapping(value = "/register", produces = MediaType.TEXT_HTML_VALUE) @ResponseBody
    public ResponseEntity<Resource> register() { return page("register.html"); }
    @GetMapping(value = "/recover", produces = MediaType.TEXT_HTML_VALUE) @ResponseBody
    public ResponseEntity<Resource> recover() { return page("recover.html"); }
    @GetMapping(value = "/security", produces = MediaType.TEXT_HTML_VALUE) @ResponseBody
    public ResponseEntity<Resource> security() { return page("security.html"); }
    @GetMapping(value = "/my", produces = MediaType.TEXT_HTML_VALUE) @ResponseBody
    public ResponseEntity<Resource> my() { return page("my.html"); }
    @GetMapping(value = "/accounts", produces = MediaType.TEXT_HTML_VALUE) @ResponseBody
    public ResponseEntity<Resource> accounts() { return page("accounts.html"); }
    @GetMapping(value = "/live-orders", produces = MediaType.TEXT_HTML_VALUE) @ResponseBody
    public ResponseEntity<Resource> liveOrders() { return page("live-orders.html"); }
    @GetMapping(value = "/admin", produces = MediaType.TEXT_HTML_VALUE) @ResponseBody
    public ResponseEntity<Resource> admin() { return page("admin.html"); }
    @GetMapping(value = "/admin-2fa", produces = MediaType.TEXT_HTML_VALUE) @ResponseBody
    public ResponseEntity<Resource> admin2fa() { return page("admin-2fa.html"); }

    @GetMapping("/login.html") public String loginHtml() { return "redirect:/login"; }
    @GetMapping("/register.html") public String registerHtml() { return "redirect:/register"; }
    @GetMapping("/recover.html") public String recoverHtml() { return "redirect:/recover"; }
    @GetMapping("/security.html") public String securityHtml() { return "redirect:/security"; }
    @GetMapping("/my.html") public String myHtml() { return "redirect:/my"; }
    @GetMapping("/accounts.html") public String accountsHtml() { return "redirect:/accounts"; }
    @GetMapping("/live-orders.html") public String liveOrdersHtml() { return "redirect:/live-orders"; }
    @GetMapping("/admin.html") public String adminHtml() { return "redirect:/admin"; }
    @GetMapping("/admin-2fa.html") public String admin2faHtml() { return "redirect:/admin-2fa"; }

    private static ResponseEntity<Resource> page(String name) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/" + name));
    }
}
