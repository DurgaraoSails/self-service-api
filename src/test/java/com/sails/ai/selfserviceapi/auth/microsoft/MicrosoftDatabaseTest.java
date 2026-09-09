package com.sails.ai.selfserviceapi.auth.microsoft;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sails.ai.selfserviceapi.auth.repository.RefreshTokenRepository;
import com.sails.ai.selfserviceapi.auth.service.AuthService;
import com.sails.ai.selfserviceapi.auth.service.RefreshTokenService;
import com.sails.ai.selfserviceapi.security.JwtProperties;
import com.sails.ai.selfserviceapi.security.JwtService;
import com.sails.ai.selfserviceapi.user.config.TrialProperties;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import com.sails.ai.selfserviceapi.user.service.EmailDomainValidator;
import com.sails.ai.selfserviceapi.user.service.UserService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Opt-in only, against a disposable local database, never the application's configured DB. */
@EnabledIfEnvironmentVariable(named = "SSO_TEST_DATABASE_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55432/sso_integration")
@SpringJUnitConfig(MicrosoftDatabaseTest.Config.class)
class MicrosoftDatabaseTest {
    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, RefreshTokenRepository.class})
    @Import({MicrosoftAuthorizationStore.class, MicrosoftProvisioningService.class, AuthService.class, RefreshTokenService.class, UserService.class})
    static class Config {
        @Bean DataSource dataSource() {
            var source = new DriverManagerDataSource(System.getenv("SSO_TEST_DATABASE_URL"), "postgres", "");
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            return source;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.sails.ai.selfserviceapi.user.entity", "com.sails.ai.selfserviceapi.auth.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean JwtService jwtService() {
            var jwt = mock(JwtService.class);
            when(jwt.issueAccessToken(any())).thenReturn("test-access");
            when(jwt.accessTokenTtlSeconds()).thenReturn(1800L);
            return jwt;
        }
        @Bean JwtProperties jwtProperties() { return new JwtProperties("test", "unused", "unused", Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(15), "1", List.of()); }
        @Bean TrialProperties trialProperties() { return new TrialProperties(14); }
        @Bean EmailDomainValidator emailDomainValidator() { return mock(EmailDomainValidator.class); }
    }

    @Autowired MicrosoftAuthorizationStore store;
    @Autowired MicrosoftProvisioningService provisioning;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refresh;
    @Autowired AuthService auth;

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE TABLE users, microsoft_authorizations CASCADE");
    }

    @Test void onlyOneConcurrentCompletionCanConsumeAState() throws Exception {
        String verifier = "v".repeat(43);
        var pending = store.create(MicrosoftAuthorizationStore.challenge(verifier));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Boolean> attempt = () -> {
                gate.await();
                try { return store.consume(pending.state(), verifier).equals(pending.nonce()); }
                catch (com.sails.ai.selfserviceapi.common.exception.ApiException ex) { return false; }
            };
            var first = pool.submit(attempt); var second = pool.submit(attempt); gate.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test void incorrectVerifierDoesNotConsumeAndExpiredStateCannotBeUsed() {
        String verifier = "v".repeat(43);
        var pending = store.create(MicrosoftAuthorizationStore.challenge(verifier));
        assertThatThrownBy(() -> store.consume(pending.state(), "w".repeat(43))).isInstanceOf(com.sails.ai.selfserviceapi.common.exception.ApiException.class);
        assertThat(store.consume(pending.state(), verifier)).isEqualTo(pending.nonce());
        var expired = store.create(MicrosoftAuthorizationStore.challenge(verifier));
        jdbc.update("UPDATE microsoft_authorizations SET expires_at = now() - interval '1 second'");
        assertThatThrownBy(() -> store.consume(expired.state(), verifier)).isInstanceOf(com.sails.ai.selfserviceapi.common.exception.ApiException.class);
    }

    @Test void concurrentFirstSignInsProduceOneUserAndOnlyOneFirstLogin() throws Exception {
        var identity = new MicrosoftIdentityClient.Identity(MicrosoftIdentityClientTest.TENANT, MicrosoftIdentityClientTest.OBJECT,
                "jane@sailssoftware.com", new MicrosoftIdentityClient.Profile(MicrosoftIdentityClientTest.OBJECT, "Member",
                "jane@sailssoftware.com", null, "Jane", "Doe", "Jane Doe", null, null));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<com.sails.ai.selfserviceapi.auth.service.LoginResult> attempt = () -> { gate.await(); return provisioning.signIn(identity); };
            var first = pool.submit(attempt); var second = pool.submit(attempt); gate.countDown();
            var a = first.get(15, TimeUnit.SECONDS); var b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.user().getId()).isEqualTo(b.user().getId());
            assertThat(List.of(a.firstLogin(), b.firstLogin())).containsExactlyInAnyOrder(true, false);
            assertThat(users.count()).isEqualTo(1);
            assertThat(refresh.count()).isEqualTo(2);
        }
    }

    @Test void linksCaseInsensitiveEmailAndRevokesOldRefreshTokens() {
        User existing = new User(); existing.setEmail("Jane@SailsSoftware.com");
        existing.setFirstName("Jane"); existing.setLastName("Doe"); existing.setCompanyName("Sails");
        existing.setRoles(List.of("USER", "ADMIN")); existing.setStatus(com.sails.ai.selfserviceapi.user.entity.UserStatus.ACTIVE);
        existing = users.saveAndFlush(existing);
        var oldSession = auth.issueTokensForAuthenticatedUser(existing);
        var identity = new MicrosoftIdentityClient.Identity(MicrosoftIdentityClientTest.TENANT, MicrosoftIdentityClientTest.OBJECT,
                "jane@sailssoftware.com", new MicrosoftIdentityClient.Profile(MicrosoftIdentityClientTest.OBJECT, "Member",
                "jane@sailssoftware.com", null, "Jane", "Doe", "Jane Doe", null, null));
        var result = provisioning.signIn(identity);
        assertThat(result.user().getId()).isEqualTo(existing.getId());
        assertThat(result.user().getRoles()).contains("ADMIN");
        assertThatThrownBy(() -> auth.refresh(oldSession.tokenPair().refreshToken())).isInstanceOf(RuntimeException.class);
    }
}
