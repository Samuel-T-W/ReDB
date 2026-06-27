import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import App from "../App";
import { LATEST_IMPLEMENTED } from "../data/iterations";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe("App routing", () => {
  it("redirects the root path to the latest implemented iteration", () => {
    renderAt("/");
    expect(
      screen.getByRole("heading", { level: 1, name: LATEST_IMPLEMENTED.name }),
    ).toBeInTheDocument();
  });

  it("redirects unknown paths to the latest iteration", () => {
    renderAt("/nope/123");
    expect(
      screen.getByRole("heading", { level: 1, name: LATEST_IMPLEMENTED.name }),
    ).toBeInTheDocument();
  });

  it("renders the live demo on an implemented iteration", () => {
    renderAt(`/iteration/${LATEST_IMPLEMENTED.id}`);
    expect(screen.getByRole("heading", { name: "Run query" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "▶ Run" })).toBeInTheDocument();
  });

  it("shows a placeholder instead of the demo for a planned iteration", () => {
    renderAt("/iteration/2");
    expect(screen.getByRole("heading", { name: "Not built yet" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Run query" })).not.toBeInTheDocument();
  });

  it("lists every iteration on the planned-work page", () => {
    renderAt("/planned");
    const main = screen.getByRole("main");
    expect(within(main).getByText(/Concurrency/)).toBeInTheDocument();
  });
});
