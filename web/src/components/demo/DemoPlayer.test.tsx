import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import DemoPlayer from "./DemoPlayer";

describe("DemoPlayer", () => {
  it("starts at the first event with playback paused", () => {
    render(<DemoPlayer />);
    expect(screen.getByText(/^1 \/ \d+ events$/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "▶ Run" })).toBeInTheDocument();
    // Stepping backward is impossible at the start.
    expect(screen.getByRole("button", { name: "◀ Step" })).toBeDisabled();
  });

  it("advances the cursor when stepping forward", () => {
    render(<DemoPlayer />);
    fireEvent.click(screen.getByRole("button", { name: "Step ▶" }));
    expect(screen.getByText(/^2 \/ \d+ events$/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "◀ Step" })).toBeEnabled();
  });

  it("rebuilds the run when the access method changes", () => {
    render(<DemoPlayer />);
    fireEvent.click(screen.getByRole("button", { name: "Step ▶" }));
    expect(screen.getByText(/^2 \/ \d+ events$/)).toBeInTheDocument();

    // Switching to full scan regenerates the trace and resets playback to event 1.
    fireEvent.click(screen.getByRole("button", { name: "full scan" }));
    expect(screen.getByText(/^1 \/ \d+ events$/)).toBeInTheDocument();
  });
});
