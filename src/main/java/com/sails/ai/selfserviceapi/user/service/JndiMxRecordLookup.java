package com.sails.ai.selfserviceapi.user.service;

import com.sails.ai.selfserviceapi.user.config.EmailDomainValidationProperties;
import java.util.Hashtable;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JndiMxRecordLookup implements MxRecordLookup {

    private final EmailDomainValidationProperties properties;

    public JndiMxRecordLookup(EmailDomainValidationProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean hasMxRecords(String domain) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(properties.timeoutMillis()));
        env.put("com.sun.jndi.dns.timeout.retries", String.valueOf(properties.retries()));

        try {
            DirContext ctx = new InitialDirContext(env);
            Attributes attributes = ctx.getAttributes(domain, new String[]{"MX"});
            Attribute mx = attributes.get("MX");
            return mx != null && mx.size() > 0;
        } catch (NamingException e) {
            log.debug("MX lookup failed for domain {}: {}", domain, e.getMessage());
            return false;
        }
    }
}
