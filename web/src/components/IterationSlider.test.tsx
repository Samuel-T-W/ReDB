import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import IterationSlider from "./IterationSlider";
import { ITERATIONS } from "../data/iterations";

describe("IterationSlider", () => {
  it("renders a discrete step per iteration with the current one selected", () => {
    render(
      <MemoryRouter>
        <IterationSlider currentId={1} />
      </MemoryRouter>,
    );

    expect(screen.getAllByRole("tab")).toHaveLength(ITERATIONS.length);
    expect(screen.getByRole("tab", { name: /v1/i })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: /v2/i })).toHaveAttribute("aria-selected", "false");
  });
});
