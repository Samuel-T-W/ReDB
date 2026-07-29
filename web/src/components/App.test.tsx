import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import App from "../App";
import { ITERATIONS, LATEST_IMPLEMENTED } from "../data/iterations";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe("App routing", () => {
  it("renders the home page at the root path", () => {
    renderAt("/");
    expect(
      screen.getByRole("heading", { level: 1, name: /read-only query engine/i }),
    ).toBeInTheDocument();
  });

  it("redirects unknown paths to the home page", () => {
    renderAt("/nope/123");
    expect(
      screen.getByRole("heading", { level: 1, name: /read-only query engine/i }),
    ).toBeInTheDocument();
  });

  it("renders the simulation on an implemented iteration", () => {
    renderAt(`/iteration/${LATEST_IMPLEMENTED.id}`);
    expect(screen.getByRole("heading", { name: "Run query" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "▶ Run" })).toBeInTheDocument();
  });

  it("shows a placeholder instead of the simulation for a planned iteration", () => {
    renderAt("/iteration/2");
    expect(screen.getByRole("heading", { name: "Not built yet" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Run query" })).not.toBeInTheDocument();
  });
});

describe("Iteration view tabs", () => {
  it("opens on the simulation tab", () => {
    renderAt(`/iteration/${LATEST_IMPLEMENTED.id}`);
    expect(screen.getByRole("tab", { name: "Simulation" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  it("swaps the simulation out for the panel whose tab is picked", () => {
    renderAt(`/iteration/${LATEST_IMPLEMENTED.id}`);

    fireEvent.click(screen.getByRole("tab", { name: "Performance" }));
    expect(screen.getByRole("heading", { name: "Takeaways" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Run query" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "How it works" }));
    expect(
      screen.getByRole("heading", { name: LATEST_IMPLEMENTED.explanation[0].title }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Takeaways" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Simulation" }));
    expect(screen.getByRole("heading", { name: "Run query" })).toBeInTheDocument();
  });

  it("offers no performance tab for an iteration without benchmark data", () => {
    renderAt("/iteration/2");
    expect(screen.queryByRole("tab", { name: "Performance" })).not.toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "How it works" })).toBeInTheDocument();
  });
});

describe("Home page", () => {
  it("explains the read-only aim of the engine", () => {
    renderAt("/");
    const aim = screen.getByRole("region", { name: "What ReDB is trying to be" });
    expect(within(aim).getByRole("heading", { name: "Read-only, on purpose" })).toBeInTheDocument();
    expect(
      within(aim).getByRole("heading", { name: "General queries, not one fixed plan" }),
    ).toBeInTheDocument();
  });

  it("lists every iteration in the roadmap section", () => {
    renderAt("/");
    const roadmap = screen.getByRole("region", { name: "Planned work" });
    for (const iteration of ITERATIONS) {
      expect(within(roadmap).getByRole("heading", { name: iteration.name })).toBeInTheDocument();
    }
  });

  it("links to the simulation of the latest implemented iteration", () => {
    renderAt("/");
    expect(screen.getByRole("link", { name: /Open the simulation/ })).toHaveAttribute(
      "href",
      `/iteration/${LATEST_IMPLEMENTED.id}`,
    );
  });
});
