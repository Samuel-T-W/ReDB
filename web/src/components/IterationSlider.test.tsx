import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import IterationSlider from "./IterationSlider";
import { ITERATIONS } from "../data/iterations";

function renderSlider(currentId?: number) {
  return render(
    <MemoryRouter>
      <IterationSlider currentId={currentId} />
    </MemoryRouter>,
  );
}

describe("IterationSlider", () => {
  it("renders a discrete step per iteration with the current one marked", () => {
    renderSlider(1);

    expect(screen.getAllByRole("link")).toHaveLength(ITERATIONS.length);
    expect(screen.getByRole("link", { name: /v1/i })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: /v2/i })).not.toHaveAttribute("aria-current");
  });

  it("links each step to its iteration", () => {
    renderSlider(1);
    expect(screen.getByRole("link", { name: /v3/i })).toHaveAttribute("href", "/iteration/3");
  });

  it("marks nothing as current when there is no current iteration", () => {
    renderSlider();
    for (const step of screen.getAllByRole("link")) {
      expect(step).not.toHaveAttribute("aria-current");
    }
  });
});
