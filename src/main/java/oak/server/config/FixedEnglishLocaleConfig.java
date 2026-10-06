package oak.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.util.Locale;

/**
 * The API is English-only (project rule): every user-facing string — error bodies, logs, the
 * client toast that renders {@code message} — must be English. Bean-validation defaults broke
 * that (live report 2026-10-07): Hibernate Validator interpolates its stock messages through
 * the REQUEST locale, and a Russian Accept-Language (or the JVM default on a Russian machine)
 * produced "размер должен быть в диапазоне…" in the response, the [api] log line and the
 * client's inline error. A fixed resolver pins {@link LocaleContextHolder} to English for
 * every dispatched request, regardless of the Accept-Language header.
 */
@Configuration
public class FixedEnglishLocaleConfig {

    /** The well-known bean name {@link org.springframework.web.servlet.DispatcherServlet}
     *  looks up — do not rename. */
    @Bean
    public LocaleResolver localeResolver() {
        return new FixedLocaleResolver(Locale.ENGLISH);
    }
}
