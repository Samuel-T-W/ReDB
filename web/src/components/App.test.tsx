import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
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
    expect(screen.getByRole("heading", { level: 1, name: /read-only query engine/i })).toBeInTheDocument();
  });

  it("redirects unknown paths to the home page", () => {
    renderAt("/nope/123");
    expect(screen.getByRole("heading", { level: 1, name: /read-only query engine/i })).toBeInTheDocument();
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

  it("links to the live demo of the latest implemented iteration", () => {
    renderAt("/");
    expect(screen.getByRole("link", { name: /Open the live demo/ })).toHaveAttribute(
      "href",
      `/iteration/${LATEST_IMPLEMENTED.id}`,
    );
  });
});
