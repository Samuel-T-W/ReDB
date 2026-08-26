import io.sentry.Sentry;

/**
 * Optional Sentry init for the always-on QueryEngine path. Tests and CI send
 * nothing unless {@code SENTRY_DSN} is set.
 */
public final class SentryBootstrap {

    private SentryBootstrap() {
    }

    public static void initIfConfigured() {
        if (Sentry.isEnabled()) {
            return;
        }
        String dsn = System.getenv("SENTRY_DSN");
        if (dsn == null || dsn.isBlank()) {
            return;
        }
        String environment = System.getenv("SENTRY_ENVIRONMENT");
        if (environment == null || environment.isBlank()) {
            environment = "development";
        }
        final String env = environment;
        Sentry.init(options -> {
            options.setDsn(dsn);
            options.setEnvironment(env);
        });
    }

    public static void close() {
        Sentry.close();
    }
}
