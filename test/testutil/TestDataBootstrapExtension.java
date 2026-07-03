package testutil;

import java.io.IOException;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Auto-loads deterministic CSV fixtures before any test class runs. */
public final class TestDataBootstrapExtension implements BeforeAllCallback {

	@Override
	public void beforeAll(ExtensionContext context) throws IOException {
		TestDataBootstrap.ensure();
	}
}
