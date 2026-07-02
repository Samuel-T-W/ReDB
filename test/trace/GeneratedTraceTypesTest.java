package trace;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Guards against a stale web/src/types/trace.ts. The typescript-generator Maven
 * plugin regenerates that file from src/trace/*.java in the process-classes
 * phase, which runs before this test on every `mvn test`. If the working copy
 * still differs from the committed one, the Java model changed without a
 * regeneration.
 */
public class GeneratedTraceTypesTest {

	@Test
	public void committedTraceTypesMatchTheJavaModel() throws Exception {
		Process diff = new ProcessBuilder("git", "diff", "--exit-code", "--", "web/src/types/trace.ts")
				.redirectErrorStream(true)
				.start();

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		diff.getInputStream().transferTo(output);
		int exitCode = diff.waitFor();

		assertEquals(
				0,
				exitCode,
				"web/src/types/trace.ts is out of date with src/trace/*.java. Run `mvn process-classes` "
						+ "(or `npm run codegen` from web/) and commit the regenerated file:\n" + output);
	}
}
