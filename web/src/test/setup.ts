// Adds jest-dom matchers (toBeInTheDocument, toHaveTextContent, ...) to Vitest's
// expect and clears the rendered DOM between tests.
import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

// jsdom doesn't implement scrollIntoView, which EventLog calls to keep the
// active row visible. Stub it so components that auto-scroll can mount.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = vi.fn();
}

afterEach(() => {
  cleanup();
});
