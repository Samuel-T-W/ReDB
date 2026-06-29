import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import IterationSlider from "./IterationSlider";

describe("IterationSlider", () => {
  it("positions iteration ticks to match the slider stops", () => {
    render(
      <MemoryRouter>
        <IterationSlider currentId={1} />
      </MemoryRouter>,
    );

    expect(screen.getByRole("slider", { name: "Select iteration" })).toHaveValue("1");
    expect(screen.getByRole("button", { name: /v1/i })).toHaveStyle({ left: "0%" });
    expect(screen.getByRole("button", { name: /v2/i })).toHaveStyle({ left: "50%" });
    expect(screen.getByRole("button", { name: /v3/i })).toHaveStyle({ left: "100%" });
  });
});
